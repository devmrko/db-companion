package com.dbcompanion.service;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AgentCatalog.Component;
import com.dbcompanion.model.AgentCatalog.Kind;
import com.dbcompanion.model.TeamEdit.*;
import com.dbcompanion.repository.TeamEditRepository;
import com.dbcompanion.repository.TeamHistoryRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TeamEditService {
    private final SessionDataSource source;
    private final TeamEditRepository repository;
    private final TeamHistoryRepository archive;
    private final JsonMapper json;
    private final TransactionTemplate read,write;
    public TeamEditService(SessionDataSource source,TeamEditRepository repository,TeamHistoryRepository archive,JsonMapper json) {
        this.source=source;this.repository=repository;this.archive=archive;this.json=json;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(10);
        write=new TransactionTemplate(manager);write.setTimeout(20);
    }
    private void scope(PoolSession session,Target target) {
        TeamEditPolicy.scope(session.metadata().info().username(),session.metadata().selectedSchema(),target);
    }
    private <T> T query(PoolSession session,Target target,Supplier<T> work) {
        synchronized(session) {
            scope(session,target);source.bind(session.pool(),target.schema());
            try{return read.execute(s -> work.get());}finally{source.clear();}
        }
    }
    public Form edit(PoolSession session,Target target) {
        return query(session,target,() -> {
            repository.packageOwner(target.schema());
            var current=repository.current(target);String value=TeamEditPolicy.value(current,target.attribute());
            archive.require(target.schema());
            var agents=List.<String>of();var tasks=List.<String>of();
            if(List.of("agents","supervisor_agent").contains(target.attribute()))agents=repository.choices(Kind.AGENT);
            if("agents".equals(target.attribute()))tasks=repository.choices(Kind.TASK);
            return new Form(target,value,TeamEditPolicy.version(target,current,json),TeamEditPolicy.MAX_BYTES,agents,tasks);
        });
    }
    private Target historyTarget(HistoryTarget target) {
        if(target==null)throw new MetadataEditException(400,"Missing Team target",UiMessages.text("ui.9a31b5a59145", "Team을 선택해 주세요."));
        return new Target(target.schema(),target.team(),"agents");
    }
    public HistoryPage history(PoolSession session,HistoryTarget target,int page) {
        if(page<1||page>100_000)throw new MetadataEditException(400,"Invalid history page",UiMessages.text("ui.b89ebed2c9ae", "페이지를 확인해 주세요."));
        return query(session,historyTarget(target),() -> archive.history(target.schema(),target.team(),page));
    }
    public HistoryEntry entry(PoolSession session,HistoryTarget target,String seq) {
        if(seq==null||!seq.matches("(?:L:)?[1-9][0-9]{0,37}"))throw new MetadataEditException(400,"Invalid history sequence",UiMessages.text("ui.37ca61df2134", "이력 번호를 확인해 주세요."));
        return query(session,historyTarget(target),() -> archive.entry(target.schema(),target.team(),seq));
    }
    public synchronized HistoryPage install(PoolSession session,HistoryTarget target) {
        var selected=historyTarget(target);
        synchronized(session) {
            scope(session,selected);source.bind(session.pool(),target.schema());
            try {
                read.executeWithoutResult(s -> {
                    TeamEditPolicy.value(repository.current(selected),"agents");
                    repository.packageOwner(target.schema());
                });
                try{write.executeWithoutResult(s -> archive.install(target.schema()));}
                catch(RuntimeException ex) {
                    log("history-install",ex);
                    String detail=ex instanceof MetadataEditException m?m.userMessage():OracleErrorDetails.forDisplay(ex);
                    throw new MetadataEditException(503,"Team history install incomplete",
                            UiMessages.text("ui.808eb60b8c66", "보관 테이블 생성·확인이 중단되었습니다. 앞 단계의 생성 객체는 남아 있을 수 있습니다. 자동 재시도하지 말고 상태를 확인해 주세요.\n")+detail);
                }
                return read.execute(s -> archive.history(target.schema(),target.team(),1));
            }finally{source.clear();}
        }
    }
    private record Before(String owner,Component team,String requestId,boolean unchanged) {}
    public synchronized SaveResult save(PoolSession session,SaveRequest request) {
        if(request==null)throw new MetadataEditException(400,"Missing Team edit",UiMessages.text("ui.02e596d0283b", "저장 요청을 확인해 주세요."));
        synchronized(session) {
            Target target=request.target();scope(session,target);TeamEditPolicy.input(target,request.value(),json);
            source.bind(session.pool(),target.schema());var attempted=new AtomicBoolean();
            try {
                Before before=write.execute(s -> {
                    String owner=repository.packageOwner(target.schema());archive.require(target.schema());
                    var current=repository.current(target);String value=TeamEditPolicy.value(current,target.attribute());
                    TeamEditPolicy.verifyVersion(target,current,request.version(),json);
                    boolean unchanged=TeamEditPolicy.equivalent(target.attribute(),request.value(),value,json);
                    if(unchanged)return new Before(owner,current,null,true);
                    repository.validateReferences(target,current,request.value(),json);
                    String id=archive.before(target,current.info().id(),session.metadata().info().username(),json.writeValueAsString(current));
                    return new Before(owner,current,id,false);
                }); // Durable original first; a later package call may commit independently.
                if(before.unchanged())return new SaveResult(true,UiMessages.text("ui.e2b9c7bd1c43", "변경된 내용이 없습니다."));
                RuntimeException apiError=null;
                try {
                    write.executeWithoutResult(s -> {
                        var current=repository.current(target);
                        TeamEditPolicy.verifyVersion(target,current,request.version(),json);
                        repository.validateReferences(target,current,request.value(),json);
                        attempted.set(true);repository.save(before.owner(),target,request.value());
                    });
                }catch(RuntimeException ex) {
                    if(!attempted.get())throw ex;
                    apiError=ex;log("attribute-call",ex);
                }
                Component after;
                try{after=read.execute(s -> repository.current(target));}
                catch(RuntimeException ex){log("readback",ex);return result(false,false,apiError,UiMessages.text("ui.c6c2a2c22c9b", "저장 후 DB 재조회"),ex);}
                boolean matches=TeamEditPolicy.readbackMatches(target,before.team(),after,request.value(),json);
                String outcome=matches&&apiError==null?"VERIFIED":"UNCERTAIN";
                try {
                    write.executeWithoutResult(s -> {
                        archive.require(target.schema());
                        archive.after(target,before.requestId(),outcome,json.writeValueAsString(after));
                    });
                }catch(RuntimeException ex){log("after-archive",ex);return result(matches,false,apiError,UiMessages.text("ui.e9740076b858", "변경 후 이력 보관"),ex);}
                return result(matches,true,apiError,null,null);
            }catch(RuntimeException ex) {
                if(!attempted.get())throw ex;
                log("post-call",ex);return result(false,false,ex,UiMessages.text("ui.9c3ea0797c23", "저장 후 처리"),null);
            }finally{source.clear();}
        }
    }
    public static SaveResult result(boolean matches,boolean archived,RuntimeException apiError,String stage,RuntimeException followup) {
        boolean verified=matches&&archived&&apiError==null&&followup==null;
        if(verified)return new SaveResult(true,UiMessages.text("ui.513ee667b349", "저장했습니다."));
        String message=matches?UiMessages.text("ui.41f668d909d1", "DB 저장값은 확인했지만 후속 확인을 완료하지 못했습니다."):UiMessages.text("ui.8316ffc881d3", "Team 저장 결과를 확인해야 합니다.");
        message+=UiMessages.text("ui.94588686da34", " 변경 전 전체값은 이력에 남아 있습니다. 자동 롤백하거나 재시도하지 않았습니다.");
        if(archived)message+=UiMessages.text("ui.e65e1a31936d", " 재조회한 현재값도 보관했습니다.");
        if(stage!=null)message+=UiMessages.text("ui.e14f56280fe3", "\n중단 단계: ")+stage;
        String first=OracleErrorDetails.forDisplay(apiError),second=OracleErrorDetails.forDisplay(followup);
        if(!first.isEmpty())message+="\nSET_ATTRIBUTE: "+first;
        if(!second.isEmpty())message+=UiMessages.text("ui.2e87c0e6cdaa", "\n후속 확인: ")+second;
        return new SaveResult(false,message+UiMessages.text("ui.d416ddb94df3", "\n입력은 유지됩니다. 재저장 전에 최신 값과 이력을 확인해 주세요."));
    }
    private void log(String stage,RuntimeException ex) {
        int code=0;for(Throwable cause=ex;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql){code=sql.getErrorCode();break;}
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Team edit error: stage={}, code={}",stage,code);
    }
}
