package com.dbcompanion.service;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.ProfileEdit.*;
import com.dbcompanion.repository.ProfileEditRepository;
import com.dbcompanion.repository.ProfileHistoryRepository;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProfileEditService {
    private final SessionDataSource source;
    private final ProfileHistoryRepository archive;
    private final ProfileEditRepository repository;
    private final JsonMapper json;
    private final TransactionTemplate read, write;
    public ProfileEditService(SessionDataSource source, ProfileHistoryRepository archive, ProfileEditRepository repository, JsonMapper json) {
        this.source=source; this.archive=archive; this.repository=repository; this.json=json;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager); read.setReadOnly(true); read.setTimeout(10);
        write=new TransactionTemplate(manager); write.setTimeout(20);
    }
    public Form edit(PoolSession session, Target target) {
        synchronized(session) {
            ProfileEditPolicy.scope(session.metadata().info().username(),session.metadata().selectedSchema(),target);
            source.bind(session.pool(),target.schema());
            try { return read.execute(s -> {
                archive.packageOwner(target.schema());
                requireArchive(target);
                var snapshot=archive.currentSnapshot(target.schema(),target.profile(),true);
                String value=ProfileEditPolicy.value(snapshot,target);
                var attributes=(Map<?,?>)snapshot.get("attributes");
                var choices="credential_name".equals(target.attribute()) ? repository.credentials()
                        : repository.suggestions(target.attribute(),(String)attributes.get("provider"));
                return new Form(target,value,ProfileEditPolicy.version(target,snapshot,json),ProfileEditPolicy.MAX_BYTES,
                        choices,"object_list".equals(target.attribute()) ? session.metadata().schemas() : java.util.List.of());
            }); } finally { source.clear(); }
        }
    }
    public ObjectChoices objects(PoolSession session, Target target, String owner, String filter) {
        synchronized(session) {
            ProfileEditPolicy.scope(session.metadata().info().username(),session.metadata().selectedSchema(),target);
            if (!"object_list".equals(target.attribute()) || owner==null || !session.metadata().schemas().contains(owner))
                throw new MetadataEditException(403,"Object schema not available",UiMessages.text("ui.14d4143869eb", "조회 가능한 스키마를 선택해 주세요."));
            if (filter==null || filter.length()>128 || filter.indexOf('\0')>=0)
                throw new MetadataEditException(400,"Invalid object filter",UiMessages.text("ui.3366b6b360d4", "객체 검색어는 128자 이내로 입력해 주세요."));
            source.bind(session.pool(),target.schema());
            try { return read.execute(s -> {
                ProfileEditPolicy.value(archive.currentSnapshot(target.schema(),target.profile(),true),target);
                return repository.objects(owner,filter);
            }); } finally { source.clear(); }
        }
    }
    private void requireCredential(Target target, String value) {
        if ("credential_name".equals(target.attribute()) && !repository.credentialAvailable(value))
            throw new MetadataEditException(409,"Credential not available",UiMessages.text("ui.57e623fd5aeb", "선택한 Credential이 없거나 비활성 상태입니다. 목록을 다시 불러와 주세요. 프로필 변경은 실행하지 않았습니다."));
    }
    private void requireArchive(Target target) {
        archive.requireArchive(target.schema());
    }
    private record Before(String owner,Map<String,Object> snapshot,boolean unchanged,String requestId) {}

    // Serialize app edits across pool sessions; external writers still require the optimistic recheck.
    public synchronized SaveResult save(PoolSession session, SaveRequest request) {
        if (request==null) throw new MetadataEditException(400,"Missing profile edit",UiMessages.text("ui.02e596d0283b", "저장 요청을 확인해 주세요."));
        synchronized(session) {
            Target target=request.target();
            ProfileEditPolicy.scope(session.metadata().info().username(),session.metadata().selectedSchema(),target);
            ProfileEditPolicy.input(target,request.value(),json);
            source.bind(session.pool(),target.schema());
            var attempted=new AtomicBoolean();
            try {
                Before before=write.execute(s -> {
                    String owner=archive.packageOwner(target.schema()); requireArchive(target);
                    var current=archive.currentSnapshot(target.schema(),target.profile(),true);
                    String value=ProfileEditPolicy.value(current,target);
                    ProfileEditPolicy.verifyVersion(target,current,request.version(),json);
                    boolean unchanged=ProfileEditPolicy.equivalent(target.attribute(),request.value(),value,json);
                    if (!unchanged) requireCredential(target,request.value());
                    String requestId=unchanged?null:archive.beforeEdit(target.schema(),target.profile(),
                            String.valueOf(((Map<?,?>)current.get("profile")).get("PROFILE_ID")),target.attribute(),session.metadata().info().username(),current);
                    return new Before(owner,current,unchanged,requestId);
                }); // The original snapshot must commit before any profile API call.
                if (before.unchanged()) return new SaveResult(true,UiMessages.text("ui.e2b9c7bd1c43", "변경된 내용이 없습니다."));

                RuntimeException apiError=null;
                try {
                    write.executeWithoutResult(s -> {
                        var current=archive.currentSnapshot(target.schema(),target.profile(),true);
                        ProfileEditPolicy.verifyVersion(target,current,request.version(),json);
                        requireCredential(target,request.value());
                        attempted.set(true);
                        repository.save(before.owner(),target,request.value());
                    });
                } catch (RuntimeException ex) {
                    if (!attempted.get()) throw ex;
                    apiError=ex; log("attribute-call",ex);
                }

                Map<String,Object> after;
                try { after=read.execute(s -> archive.currentSnapshot(target.schema(),target.profile(),true)); }
                catch (RuntimeException ex) {
                    log("readback",ex);
                    return result(false,false,apiError,UiMessages.text("ui.16dc1e176e18", "저장 후 DB 값을 조회하지 못했습니다. 변경 전 이력은 보관돼 있습니다. 반영 여부를 확인해 주세요."),ex);
                }
                boolean matches=ProfileEditPolicy.readbackMatches(target,before.snapshot(),after,request.value(),json);
                String outcome=matches&&apiError==null?"VERIFIED":"UNCERTAIN";
                try {
                    write.executeWithoutResult(s -> {
                        requireArchive(target);
                        archive.afterEdit(target.schema(),target.profile(),before.requestId(),outcome,after);
                    });
                } catch (RuntimeException ex) {
                    log("after-archive",ex);
                    return result(matches,false,apiError,matches
                            ? UiMessages.text("ui.457bcb2f44b2", "DB 저장값은 확인했지만 변경 후 이력 보관에 실패했습니다. 변경 전 이력은 남아 있습니다. 자동 롤백하지 않았습니다.")
                            : UiMessages.text("ui.a118c11310ed", "DB 재조회값이 입력 또는 기존 프로필 정보와 다르고, 변경 후 이력 보관도 실패했습니다. 현재 값과 변경 전 이력을 확인해 주세요."),ex);
                }
                if (apiError!=null) return result(matches,true,apiError,matches
                        ? UiMessages.text("ui.6a5f2ab11417", "DB 호출 중 오류가 있었으나 저장값과 변경 후 이력은 확인했습니다. 자동 재시도하지 말고 현재 값과 이력을 확인해 주세요.")
                        : UiMessages.text("ui.cffe9deea796", "DB 호출 중 오류가 있었고 재조회값이 입력 또는 기존 프로필 정보와 다릅니다. 조회된 현재값은 이력에 보관했습니다."),null);
                return result(matches,true,null,matches?UiMessages.text("ui.8afc2e372dc7", "저장하고 변경 전·후 전체값을 이력에 보관했습니다.")
                        :UiMessages.text("ui.c4a24331be55", "DB 재조회값이 입력 또는 기존 프로필 정보와 다릅니다. 조회된 현재값은 이력에 보관했습니다. 자동 재시도하지 말고 확인해 주세요."),null);
            } catch(RuntimeException ex) {
                if (!attempted.get()) throw ex;
                log("post-call",ex);
                return result(false,false,ex,UiMessages.text("ui.453df57a751a", "속성 저장 호출 이후 처리를 완료하지 못했습니다. 변경 전 이력은 남아 있습니다. DB 반영 여부와 변경 후 이력을 확인해 주세요."),null);
            } finally { source.clear(); }
        }
    }
    public static SaveResult result(boolean matches, boolean archived, RuntimeException apiError, String message, RuntimeException followupError) {
        boolean verified=matches && archived && apiError==null && followupError==null;
        String detail=OracleErrorDetails.forDisplay(apiError), followup=OracleErrorDetails.forDisplay(followupError);
        if (!detail.isEmpty()) message+=UiMessages.text("ui.4eef0f5ca340", "\n속성 저장 호출: ")+detail;
        if (!followup.isEmpty()) message+=UiMessages.text("ui.2e87c0e6cdaa", "\n후속 확인: ")+followup;
        if (!verified) message+=UiMessages.text("ui.a6ed964f1488", "\n입력은 유지됩니다. 재저장 전에 상세 화면의 최신 값을 확인해 주세요.");
        return new SaveResult(verified,message);
    }
    private void log(String stage,RuntimeException error) {
        int code=0;
        for(Throwable cause=error;cause!=null;cause=cause.getCause())
            if(cause instanceof java.sql.SQLException sql){code=sql.getErrorCode();break;}
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Profile edit error: stage={}, code={}",stage,code);
    }
}
