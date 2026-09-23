package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.Scheduler;
import com.dbcompanion.model.Scheduler.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SchedulerService {
    private final SessionDataSource source;private final SchedulerRepository repository;private final RoutineSourceRepository routines;private final TransactionTemplate read;
    public SchedulerService(SessionDataSource source,SchedulerRepository repository,RoutineSourceRepository routines){
        this.source=source;this.repository=repository;this.routines=routines;
        read=new TransactionTemplate(new DataSourceTransactionManager(source));read.setReadOnly(true);read.setTimeout(10);
    }
    private String login(PoolSession session){return session.metadata().info().username();}
    private void scope(PoolSession session,String schema){if(!session.metadata().selectedSchema().equals(schema))throw new Failure(409,UiMessages.text("ui.b05af1b875ea","스키마가 변경되었습니다. 화면을 새로고침해 주세요."));}
    private <T>T query(PoolSession session,Supplier<T> work){source.bind(session.pool(),login(session));try{return read.execute(status->work.get());}finally{source.clear();}}
    public Rows list(PoolSession session,String schema,boolean refresh){synchronized(session){scope(session,schema);return session.metadata().scheduler().list(schema,refresh,()->query(session,()->repository.list(schema,login(session))));}}
    private void job(PoolSession session,String schema,String name){
        Scheduler.name(name);scope(session,schema);Rows list=list(session,schema,false);
        if(!list.status().equals("AVAILABLE"))throw new Failure(409,UiMessages.text("scheduler.listRequired","작업 목록을 먼저 확인해 주세요."));
        if(list.items().stream().noneMatch(row->name.equals(row.get("JOB_NAME"))))throw new Failure(404,UiMessages.text("scheduler.notFound","작업을 찾지 못했습니다. 목록을 갱신해 주세요."));
    }
    public Detail detail(PoolSession session,String schema,String name){synchronized(session){job(session,schema,name);return session.metadata().scheduler().detail(schema,name,()->query(session,()->repository.detail(schema,login(session),name)));}}
    public Code code(PoolSession session,String schema,String name){synchronized(session){
        job(session,schema,name);return session.metadata().scheduler().code(schema,name,()->{
            var detail=detail(session,schema,name);Action action=Scheduler.action(schema,detail);
            if(!action.status().equals("AVAILABLE"))return new Code(action,List.of(),action.status(),detail.program().error()+detail.job().error(),Instant.now().toString());
            if(!"STORED_PROCEDURE".equals(action.type()))return new Code(action,List.of(),"AVAILABLE","",Instant.now().toString());
            try{
                var definitions=query(session,()->routines.source(action.owner(),action.text(),2_000_000).definitions());
                return new Code(action,definitions,definitions.isEmpty()?"UNRESOLVED":"AVAILABLE","",Instant.now().toString());
            }catch(IllegalArgumentException ex){return new Code(action,List.of(),"UNRESOLVED","",Instant.now().toString());}
            catch(com.dbcompanion.model.FunctionCatalog.Failure ex){return new Code(action,List.of(),"LIMIT","",Instant.now().toString());}
            catch(RuntimeException ex){return new Code(action,List.of(),CredentialCatalogRepository.status(ex),CredentialCatalogRepository.error(ex),Instant.now().toString());}
        });
    }}
    public Page runs(PoolSession session,String schema,String name,String before){synchronized(session){job(session,schema,name);return session.metadata().scheduler().page(schema,name,before,()->query(session,()->repository.runs(schema,login(session),name,before)));}}
    public Rows run(PoolSession session,String schema,String name,String id){synchronized(session){job(session,schema,name);return session.metadata().scheduler().log(schema,name,id,()->query(session,()->repository.run(schema,login(session),name,id)));}}
}
