package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.db.TableRowHistorySql.Target;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.TableRowHistory.*;
import com.dbcompanion.model.VectorSearch;
import com.dbcompanion.repository.TableRowHistoryRepository;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TableRowHistoryService {
    private final SessionDataSource source;
    private final TableRowHistoryRepository repository;
    private final TransactionTemplate read,write;
    public TableRowHistoryService(SessionDataSource source,TableRowHistoryRepository repository) {
        this.source=source;this.repository=repository;var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(15);
        write=new TransactionTemplate(manager);write.setTimeout(30);
    }
    public static void scope(String login,String selected,String schema,String table,boolean changing) {
        VectorSearch.name(schema);VectorSearch.name(table);VectorSearch.scope(selected,schema);
        if(changing&&!login.equals(schema))throw new MetadataEditException(403,"Owner login required",
                UiMessages.text("ui.9a62c453b27d","설치는 해당 스키마 계정으로 로그인해 주세요. 권한은 자동 부여하지 않습니다."));
    }
    private <T>T bound(PoolSession session,String schema,String table,boolean changing,Supplier<T> work) {
        synchronized(session) {
            var meta=session.metadata();scope(meta.info().username(),meta.selectedSchema(),schema,table,changing);
            source.bind(session.pool(),schema);try{return work.get();}finally{source.clear();}
        }
    }
    private record Inspection(Target target,State state) {}
    private Inspection inspect(PoolSession session,String schema,String table) {
        var target=repository.target(schema,table);
        boolean owner=session.metadata().info().username().equals(schema);
        String archive=repository.archiveState(schema);var privileges=repository.privileges();
        boolean prepare=owner&&!archive.equals("READY")&&(!archive.equals("PREPARE")||privileges.contains("CREATE TABLE"));
        String code,reason="",detail="";boolean install=false,enable=false,disable=false;
        if(!target.supported()) {code="UNSUPPORTED";detail=String.join(", ",target.unsupported());}
        else {
            TableRowHistorySql.create(target); // Reject over-sized generated triggers before any DDL.
            var trigger=repository.trigger(target);
            if(trigger==null) {
                code="NOT_INSTALLED";install=owner&&archive.equals("READY")&&privileges.contains("CREATE TRIGGER");
                reason=!owner?"OWNER":!archive.equals("READY")?"ARCHIVE":!privileges.contains("CREATE TRIGGER")?"PRIVILEGE":"";
            } else if(!trigger.compatible()) {code="CONFLICT";reason="CODE";}
            else {
                boolean enabled=trigger.status().equals("ENABLED");disable=owner&&enabled;
                if(!trigger.validity().equals("VALID")||!archive.equals("READY")){code="INVALID";reason="INVALID";}
                else {code=enabled?"ON":"OFF";enable=owner&&!enabled;reason=owner?"":"OWNER";}
            }
        }
        return new Inspection(target,new State(code,reason,detail,archive,target.id(),target.signature(),TableRowHistorySql.triggerName(target),
                prepare,install,enable,disable,target.columns().stream().filter(c->!c.vector()).map(TableRowHistorySql.Column::name).toList(),
                target.columns().stream().filter(TableRowHistorySql.Column::vector).map(TableRowHistorySql.Column::name).toList()));
    }
    public State state(PoolSession session,String schema,String table,boolean refresh) {
        return bound(session,schema,table,false,()->session.metadata().rowHistoryState(schema,table,refresh,()->read.execute(s->inspect(session,schema,table).state())));
    }
    public static boolean allowed(State s,String action) {return switch(action){case "prepare"->s.canPrepare();case "install"->s.canInstall();case "enable"->s.canEnable();case "disable"->s.canDisable();default->false;};}
    public static void sameTarget(Request request,Target target) {
        if(!Objects.equals(request.tableId(),target.id())||!Objects.equals(request.signature(),target.signature()))
            throw new MetadataEditException(409,"Target changed",UiMessages.text("rowHistory.changed","테이블 또는 컬럼 구성이 변경됐습니다. 상태를 갱신해 주세요."));
    }
    public synchronized State change(PoolSession session,Request request) {
        if(request==null||!Set.of("prepare","install","enable","disable").contains(Objects.toString(request.action(),"")))throw new IllegalArgumentException("Invalid tracking action");
        return bound(session,request.schema(),request.table(),true,()->{
            String phase="check";boolean ddlStarted=false;
            try {
                var before=read.execute(s->inspect(session,request.schema(),request.table()));sameTarget(request,before.target());
                if(!allowed(before.state(),request.action()))throw TableRowHistoryRepository.conflict(UiMessages.text("ui.76684d51aa12","상태를 다시 확인해 주세요."));
                var target=read.execute(s->repository.target(request.schema(),request.table()));sameTarget(request,target);
                if(request.action().equals("prepare")) {
                    phase="prepare";ddlStarted=true;write.executeWithoutResult(s->repository.prepare(request.schema()));
                    session.metadata().forgetRowHistorySchema(request.schema());
                } else {
                    boolean enabled=!request.action().equals("disable");
                    if(request.action().equals("install")) {
                        phase="create";
                        read.executeWithoutResult(s->{if(repository.trigger(target)!=null)throw TableRowHistoryRepository.conflict("Trigger already exists; refresh status");});
                        ddlStarted=true;write.executeWithoutResult(s->repository.create(target));
                    }
                    phase="verify";read.executeWithoutResult(s->{
                        sameTarget(request,repository.target(request.schema(),request.table()));repository.requireCompatible(target,enabled);
                        if(enabled&&!repository.archiveState(request.schema()).equals("READY"))throw TableRowHistoryRepository.conflict("History archive is not ready");
                    });
                    phase=enabled?"enable":"disable";ddlStarted=true;write.executeWithoutResult(s->repository.toggle(target,enabled));
                }
                phase="result";var result=read.execute(s->inspect(session,request.schema(),request.table()).state());
                return session.metadata().rowHistoryState(request.schema(),request.table(),true,()->result);
            } catch(RuntimeException ex) {
                session.metadata().forgetRowHistorySchema(request.schema());
                String raw=ex instanceof MetadataEditException edit?edit.userMessage():OracleErrorDetails.forDisplay(ex);
                throw new MetadataEditException(ex instanceof MetadataEditException edit?edit.status():503,"Table history stopped at "+phase,
                        UiMessages.text("rowHistory.stopped","이력 관리 {0} 단계에서 중단했습니다. {1}",phase,raw)
                                +UiMessages.text(ddlStarted?"ui.80ca13a118f5":"ui.c83683b9d690",ddlStarted?" 앞 단계의 DB 객체 변경은 남아 있을 수 있습니다. 상태를 갱신해 주세요.":" DB 변경은 실행하지 않았습니다."));
            }
        });
    }
    public Page history(PoolSession session,String schema,String table,int page) {
        return bound(session,schema,table,false,()->read.execute(s->repository.page(schema,table,page)));
    }
    public Entry entry(PoolSession session,String schema,String table,String seq) {
        return bound(session,schema,table,false,()->read.execute(s->repository.entry(schema,table,seq)));
    }
}
