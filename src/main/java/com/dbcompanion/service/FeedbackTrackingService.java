package com.dbcompanion.service;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.db.FeedbackTrackingSql.Target;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AiFeedback.Query;
import com.dbcompanion.repository.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FeedbackTrackingService {
    private final SessionDataSource source;
    private final DatabaseRepository catalog;
    private final FeedbackTrackingRepository repository;
    private final TransactionTemplate read,write;
    public FeedbackTrackingService(SessionDataSource source,DatabaseRepository catalog,FeedbackTrackingRepository repository) {
        this.source=source;this.catalog=catalog;this.repository=repository;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(15);
        write=new TransactionTemplate(manager);write.setTimeout(30);
    }
    public record Request(String schema,String profile,String profileId,Long tableId,String action) {}
    public record State(String code,String label,String detail,String profileId,Long tableId,String triggerName,
                        String archive,boolean canPrepare,boolean canInstall,boolean canEnable,boolean canDisable) {}
    private <T> T bound(PoolSession session,String schema,String profile,boolean changing,Supplier<T> action) {
        synchronized(session) {
            if(schema==null||profile==null)throw new IllegalArgumentException("Selected schema and profile required");
            var meta=session.metadata();var query=new Query(schema,profile,"","",1);
            AiFeedbackService.ownScope(meta.info().username(),meta.selectedSchema(),query);
            if(schema.isBlank()||!meta.selectedSchema().equals(schema)||profile.isBlank())throw new IllegalArgumentException("Selected schema and profile required");
            if(changing&&!meta.info().username().equals(schema))throw new MetadataEditException(403,"Owner login required",UiMessages.text("ui.9a62c453b27d", "설치는 해당 스키마 계정으로 로그인해 주세요. 권한은 자동 부여하지 않습니다."));
            source.bind(session.pool(),schema);
            try{return action.get();}finally{source.clear();}
        }
    }
    private String profileId(PoolSession session,String schema,String profile) {
        var profiles=catalog.profiles(schema,schema.equals(session.metadata().info().username()),profile);
        if(profiles.size()!=1)throw new MetadataEditException(404,"Profile unavailable",UiMessages.text("ui.63e7052801ea", "프로필을 찾을 수 없습니다."));return profiles.getFirst().id();
    }
    public State state(PoolSession session,String schema,String profile) {
        return bound(session,schema,profile,false,()->read.execute(s->inspect(session,schema,profile)));
    }
    private State inspect(PoolSession session,String schema,String profile) {
        String id=profileId(session,schema,profile);Long tableId=repository.tableId(schema,profile);
        boolean owner=session.metadata().info().username().equals(schema);
        var privileges=repository.privileges();String archive=repository.archiveState(schema);
        boolean prepare=owner&&!archive.equals("READY")&&(!archive.equals("PREPARE")||privileges.contains("CREATE TABLE"));
        if(tableId==null)return new State("NO_SOURCE",UiMessages.text("ui.eccceab88c3f", "원천 테이블 없음"),UiMessages.text("ui.8223d22f433c", "첫 Feedback 등록 후 설치할 수 있습니다."),id,null,null,archive,prepare,false,false,false);
        var target=new Target(schema,profile,id,tableId);repository.requireShape(target);
        var trigger=repository.trigger(target);String name=FeedbackTrackingSql.triggerName(target);
        String restriction=owner?"":UiMessages.text("ui.01be4d3448d6", "관리하려면 ")+schema+UiMessages.text("ui.33f3d742f298", " 계정으로 로그인해 주세요.");
        if(trigger==null) {
            boolean canInstall=owner&&archive.equals("READY")&&privileges.contains("CREATE TRIGGER");
            String detail=!archive.equals("READY")?UiMessages.text("ui.abda6e1fbdee", "Feedback 공통 이력 준비가 필요합니다."):!owner?restriction:
                    !privileges.contains("CREATE TRIGGER")?UiMessages.text("ui.b30dfffbec92", "CREATE TRIGGER 권한이 필요합니다. 관리자에게 요청해 주세요."):"";
            return new State("NOT_INSTALLED",UiMessages.text("ui.c9b6b3d43ae9", "트리거 미설치"),detail,id,tableId,name,archive,prepare,canInstall,false,false);
        }
        if(!trigger.compatible())return new State("CONFLICT",UiMessages.text("ui.34f15d3f98ec", "트리거 확인 필요"),UiMessages.text("ui.d979d2c07747", "기존 코드 또는 대상이 다릅니다. 자동 교체하지 않습니다."),id,tableId,name,archive,prepare,false,false,false);
        boolean enabled="ENABLED".equals(trigger.status());
        if(!"VALID".equals(trigger.validity())||!archive.equals("READY"))return new State("INVALID",UiMessages.text("ui.0862914f278e", "추적 비정상"),UiMessages.text("ui.d0aee68bf15a", "트리거 컴파일 또는 공통 이력 상태를 확인해 주세요."),id,tableId,name,archive,prepare,false,false,owner&&enabled);
        return new State(enabled?"ON":"OFF",enabled?UiMessages.text("ui.f4054ea64dad", "트리거 켜짐"):UiMessages.text("ui.a9fce6f80008", "트리거 꺼짐"),restriction,id,tableId,name,archive,prepare,false,owner&&!enabled,owner&&enabled);
    }
    public synchronized State change(PoolSession session,Request request) {
        if(request==null||request.action()==null||!Set.of("prepare","install","enable","disable").contains(request.action()))throw new IllegalArgumentException("Invalid tracking action");
        return bound(session,request.schema(),request.profile(),true,()->{
            String stage=UiMessages.text("ui.f3b7242cce41", "설치 전 확인");
            try {
                var before=read.execute(s->inspect(session,request.schema(),request.profile()));
                if(!Objects.equals(before.profileId(),request.profileId())||!Objects.equals(before.tableId(),request.tableId()))
                    throw new MetadataEditException(409,"Tracking target changed",UiMessages.text("ui.9e845df5dc6c", "프로필 또는 Feedback 테이블이 변경됐습니다. 상태를 다시 확인해 주세요."));
                boolean allowed=switch(request.action()) {case "prepare"->before.canPrepare();case "install"->before.canInstall();case "enable"->before.canEnable();default->before.canDisable();};
                if(!allowed)throw new MetadataEditException(409,"Tracking action unavailable",before.label()+" · "+before.detail());
                if(request.action().equals("prepare")) {
                    stage=UiMessages.text("ui.604768ed0b76", "공통 이력 준비");write.executeWithoutResult(s->repository.prepare(request.schema()));
                }else {
                    var target=new Target(request.schema(),request.profile(),before.profileId(),before.tableId());
                    // Re-read both identities immediately before DDL; no stale profile/table reuse.
                    read.executeWithoutResult(s->{
                        if(!before.profileId().equals(profileId(session,request.schema(),request.profile()))||!before.tableId().equals(repository.tableId(request.schema(),request.profile())))
                            throw new MetadataEditException(409,"Target replaced",UiMessages.text("ui.49dce8709421", "대상이 재생성됐습니다. 설치를 중단합니다."));
                        repository.requireShape(target);
                    });
                    boolean enable=!request.action().equals("disable");
                    if(request.action().equals("install")) {
                        stage=UiMessages.text("ui.1fe9de181080", "트리거 생성 (꺼짐)");
                        read.executeWithoutResult(s->{if(repository.trigger(target)!=null)throw new MetadataEditException(409,"Trigger appeared",UiMessages.text("ui.7b35e453cc2c", "트리거가 이미 생성됐습니다. 상태를 갱신해 주세요."));});
                        write.executeWithoutResult(s->repository.create(target));
                    }
                    stage=UiMessages.text("ui.e0661faa37b5", "트리거 원문·컴파일 확인");read.executeWithoutResult(s->repository.requireCompatible(target,enable));
                    if(enable)read.executeWithoutResult(s->{if(!repository.archiveState(target.schema()).equals("READY"))throw new MetadataEditException(409,"History unavailable",UiMessages.text("ui.b6fc5549e9f5", "공통 이력이 준비되지 않았습니다."));});
                    stage=enable?UiMessages.text("ui.64508ccccc70", "트리거 켜기"):UiMessages.text("ui.db37996c412a", "트리거 끄기");write.executeWithoutResult(s->repository.toggle(target,enable));
                }
                stage=UiMessages.text("ui.a9184a56e9b0", "최종 상태 조회");
                return read.execute(s->inspect(session,request.schema(),request.profile()));
            }catch(RuntimeException ex) {
                String raw=ex instanceof MetadataEditException edit?edit.userMessage():OracleErrorDetails.forDisplay(ex);
                if(raw.isBlank())raw=ex.getMessage();
                throw new MetadataEditException(ex instanceof MetadataEditException edit?edit.status():503,"Feedback tracking stopped at "+stage,
                        stage+UiMessages.text("ui.9aaf393719f7", "에서 중단했습니다. ")+raw+(stage.equals(UiMessages.text("ui.f3b7242cce41", "설치 전 확인"))?UiMessages.text("ui.c83683b9d690", " DB 변경은 실행하지 않았습니다."):UiMessages.text("ui.80ca13a118f5", " 앞 단계의 DB 객체 변경은 남아 있을 수 있습니다. 상태를 갱신해 주세요.")));
            }
        });
    }
    public FeedbackTrackingRepository.Page history(PoolSession session,String schema,String profile,int page) {
        return bound(session,schema,profile,false,()->read.execute(s->{profileId(session,schema,profile);return repository.page(schema,profile,page);}));
    }
    public FeedbackTrackingRepository.Entry entry(PoolSession session,String schema,String profile,String seq) {
        return bound(session,schema,profile,false,()->read.execute(s->{profileId(session,schema,profile);return repository.entry(schema,profile,seq);}));
    }
}
