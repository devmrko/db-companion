package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.HistoryAccess.*;
import com.dbcompanion.model.MetadataEdit.Target;
import com.dbcompanion.repository.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class HistoryAccessService {
    private final SessionDataSource source;
    private final DatabaseRepository database;
    private final MetadataRepository metadata;
    private final MetadataHistoryRepository history;
    private final HistoryReadinessRepository readiness;
    private final MetadataHistoryService switches;
    private final HistoryAccessRepository access;
    private final TransactionTemplate read,write;
    public HistoryAccessService(SessionDataSource source,DatabaseRepository database,MetadataRepository metadata,
            MetadataHistoryRepository history,HistoryReadinessRepository readiness,MetadataHistoryService switches,JdbcTemplate jdbc) {
        this.source=source;this.database=database;this.metadata=metadata;this.history=history;this.readiness=readiness;this.switches=switches;
        access=new HistoryAccessRepository(jdbc);
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(120);
        write=new TransactionTemplate(manager);write.setTimeout(30);
    }
    public Preview preview(PoolSession session,String schema,String profile,String table) {
        return bound(session,schema,read,()-> {
            String user=access.requireAdministrator();
            String raw=table==null?objectList(schema,user,profile):null;
            var names=table==null?targets(schema,raw,database.tables(schema).stream().map(t->t.name()).toList()):List.of(table);
            var items=new ArrayList<Item>();
            for(String name:names) {
                // The profile was resolved against the visible catalog above. Do not perform an
                // N-times full source/trigger inspection just to render a selection list. The apply
                // preflight still verifies every selected object before any write.
                if(table==null) {
                    if(name.startsWith("DBC_MH_")||name.startsWith("DBC_META")) throw invalid("history.access.internal","이력 관리 객체는 수집 대상으로 선택할 수 없습니다.");
                    items.add(new Item(name,null,access.grants(user,new Target(schema,name,null))));
                    continue;
                }
                Target target=new Target(schema,name,null); requireTarget(target);
                var config=history.configuration(target,false);
                if(config.triggerOwner()!=null&&!user.equals(config.triggerOwner())) throw invalid("history.access.owner","기록된 트리거 소유 계정으로 로그인해 주세요.");
                items.add(new Item(name,history.state(target),access.grants(user,target)));
            }
            return new Preview(UUID.randomUUID().toString(),schema,profile,raw,Instant.now(),List.copyOf(items),access.users());
        });
    }
    public List<Result> apply(PoolSession session,Preview preview,Apply request) {
        synchronized(session) {
            validate(preview,request,Instant.now());
            var names=new LinkedHashSet<>(request.tables());
            String owner=bound(session,preview.schema(),read,()-> {
                String user=access.requireAdministrator();
                if(preview.objectList()!=null&&!Objects.equals(preview.objectList(),objectList(preview.schema(),user,preview.profile())))
                    throw invalid("history.access.stale","프로필 또는 선택 범위가 변경되었습니다. 다시 확인해 주세요.");
                if(!request.allUsers()&&!access.users().containsAll(request.users())) throw invalid("history.access.user","현재 DB에 존재하는 사용자를 선택해 주세요.");
                if(request.users().contains(user)) throw invalid("history.access.self","관리 패키지 소유자의 고유 권한은 이 화면에서 회수할 수 없습니다.");
                for(String name:names) {
                    Target target=new Target(preview.schema(),name,null); requireTarget(target);
                    var config=history.configuration(target);
                    if(config.triggerOwner()!=null&&!user.equals(config.triggerOwner())) throw invalid("history.access.owner","기록된 트리거 소유 계정으로 로그인해 주세요.");
                    if(request.operation()==Operation.ENABLE||request.operation()==Operation.DISABLE) {
                        var plan=readiness.prepare(target,request.operation()==Operation.ENABLE);
                        if(!plan.decision().allowed()) throw new MetadataEditException(403,"History management privilege missing",plan.decision().message());
                        if(request.operation()==Operation.ENABLE&&(plan.auditUpgradeRequired()||!plan.legacyTriggers().isEmpty()))
                            throw invalid("history.access.upgrade","일괄 적용 전에 설치 확인에서 구버전 코드를 업데이트해 주세요.");
                    } else {
                        if(!user.equals(config.triggerOwner())||!history.state(target).installed())
                            throw invalid("history.access.installFirst","먼저 대상의 이력 수집을 설치·설정한 뒤 권한을 부여해 주세요.");
                        for(var asset:HistorySql.assets(target,user)) history.validateAsset(target,asset);
                    }
                }
                if(request.operation()!=Operation.ENABLE&&request.operation()!=Operation.DISABLE) access.missing(user,history);
                return user;
            });
            var results=new ArrayList<Result>(); boolean stopped=false;
            for(String name:names) {
                if(stopped) { results.add(new Result(name,false,UiMessages.text("history.access.notRun","앞선 오류로 실행하지 않았습니다.")));continue; }
                Target target=new Target(preview.schema(),name,null);
                try {
                    if(request.operation()==Operation.ENABLE||request.operation()==Operation.DISABLE)
                        switches.toggle(session,target,request.operation()==Operation.ENABLE);
                    else bound(session,preview.schema(),write,()-> {
                        if(!owner.equals(access.requireAdministrator())) throw HistoryAccessRepository.denied();
                        access.install(owner,history);access.register(owner,target,history);
                        for(String user:request.allUsers()?List.of("PUBLIC"):request.users()) access.grant(owner,target,user,request.operation());
                        return null;
                    });
                    results.add(new Result(name,true,UiMessages.text("history.access.applied","적용 완료")));
                } catch(RuntimeException ex) {
                    stopped=true;
                    results.add(new Result(name,false,(ex instanceof MetadataEditException edit?edit.userMessage():UiMessages.text("history.access.failed","DB 작업을 확인하지 못했습니다."))
                        +" "+UiMessages.text("history.access.partial","DDL·권한 작업은 일부 적용되어 있을 수 있습니다. 다시 조회해 확인하세요. 자동 재시도하지 않습니다.")));
                } finally { session.metadata().forgetHistorySchema(preview.schema()); }
            }
            return List.copyOf(results);
        }
    }
    private void requireTarget(Target target) {
        if(target.table()==null||target.table().startsWith("DBC_MH_")||target.table().startsWith("DBC_META")) throw invalid("history.access.internal","이력 관리 객체는 수집 대상으로 선택할 수 없습니다.");
        metadata.requireTarget(target);
    }
    private String objectList(String schema,String user,String profile) {
        if(profile==null||profile.isBlank()||database.profiles(schema,schema.equals(user),profile).isEmpty()) throw invalid("history.access.profile","Select AI 프로필을 선택해 주세요.");
        return database.profileObjectList(schema,schema.equals(user),profile);
    }
    public static List<String> targets(String schema,String raw,List<String> visible) {
        var result=new LinkedHashSet<String>();
        try {
            var root=JsonMapper.builder().build().readTree(Objects.requireNonNull(raw));
            if(!root.isArray()||root.isEmpty()||root.size()>1000) throw new IllegalArgumentException();
            for(var entry:root) {
                if(!entry.path("owner").isString()) throw new IllegalArgumentException();
                if(!schema.equals(entry.path("owner").asString())) throw invalid("history.access.crossSchema","다른 스키마의 객체가 포함되어 있습니다. 스키마별 개별 설정을 사용하세요. 일부만 자동 적용하지 않습니다.");
                if(!entry.has("name")) result.addAll(visible.stream().filter(n->!n.startsWith("DBC_MH_")&&!n.startsWith("DBC_META")).toList());
                else {
                    if(!entry.path("name").isString()||!visible.contains(entry.path("name").asString())) throw new IllegalArgumentException();
                    result.add(entry.path("name").asString());
                }
            }
            if(result.isEmpty()||result.size()>50) throw invalid("history.access.limit","한 번에 1~50개 테이블·뷰를 설정할 수 있습니다. 프로필 범위를 좁혀 주세요.");
            return List.copyOf(result);
        } catch(MetadataEditException ex) { throw ex; }
        catch(RuntimeException ex) { throw invalid("history.access.invalidList","object_list의 객체를 모두 확인하지 못했습니다. 프로필과 객체 조회 권한을 확인해 주세요."); }
    }
    public static void validate(Preview preview,Apply request,Instant now) {
        if(preview==null||request==null||!preview.token().equals(request.token())||preview.createdAt().plusSeconds(600).isBefore(now))
            throw invalid("history.access.stale","프로필 또는 선택 범위가 변경되었습니다. 다시 확인해 주세요.");
        if(request.operation()==null||request.tables()==null||request.tables().isEmpty()||request.tables().size()>50
                ||request.tables().stream().distinct().count()!=request.tables().size()
                ||!preview.items().stream().map(Item::name).toList().containsAll(request.tables())) throw invalid("history.access.selection","확인한 목록에서 대상 객체를 선택해 주세요.");
        if(request.users()==null||request.users().size()>100||request.users().stream().anyMatch(Objects::isNull)
                ||request.users().contains("PUBLIC")||request.users().stream().distinct().count()!=request.users().size()
                ||request.allUsers()&&!request.users().isEmpty()) throw invalid("history.access.user","현재 DB에 존재하는 사용자를 선택해 주세요.");
        if(request.operation()!=Operation.ENABLE&&request.operation()!=Operation.DISABLE&&!request.allUsers()&&request.users().isEmpty())
            throw invalid("history.access.user","현재 DB에 존재하는 사용자를 선택해 주세요.");
    }
    private static MetadataEditException invalid(String key,String text) { return new MetadataEditException(409,"History access setup rejected",UiMessages.text(key,text)); }
    private <T>T bound(PoolSession session,String schema,TransactionTemplate tx,Supplier<T> work) {
        synchronized(session) {
            if(!schema.equals(session.metadata().selectedSchema())) throw invalid("history.access.stale","프로필 또는 선택 범위가 변경되었습니다. 다시 확인해 주세요.");
            source.bind(session.pool(),session.connectionSchema());
            try { return tx.execute(s->work.get()); } finally { source.clear(); }
        }
    }
}
