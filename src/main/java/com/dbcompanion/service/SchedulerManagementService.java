package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.SchedulerManagement;
import com.dbcompanion.model.SchedulerManagement.*;
import com.dbcompanion.model.OrdsManagement;
import com.dbcompanion.repository.SchedulerManagementRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class SchedulerManagementService {
    private record Pending(Preview preview,Instant expires) {}
    private final Map<PoolSession,Pending> pending=Collections.synchronizedMap(new WeakHashMap<>());
    private final SessionDataSource source;private final SchedulerManagementRepository repository;
    public SchedulerManagementService(SessionDataSource source,SchedulerManagementRepository repository){this.source=source;this.repository=repository;}
    private String owner(PoolSession s){String u=s.metadata().info().username();SchedulerManagement.require(u.equals(s.metadata().selectedSchema()),"ownSchema");return u;}
    private <T>T run(PoolSession s,Supplier<T> f){source.bind(s.pool(),owner(s));try{return f.get();}finally{source.clear();}}
    public State state(PoolSession s){synchronized(s){return run(s,repository::state);}}
    public Preview preview(PoolSession s,Request input){synchronized(s){pending.remove(s);return run(s,()->{
        State state=repository.state();SchedulerManagement.require(input!=null&&Objects.equals(input.revision(),OrdsManagement.digest(state)),"stale");
        Draft d=input.draft();String sql=SchedulerManagement.sql(d,state,owner(s));
        var existing=SchedulerManagement.target(state,d.name());
        var p=new Preview(UUID.randomUUID().toString(),d.action()+" "+d.schema()+"."+d.name(),sql,OrdsManagement.digest(state),d,d.action().equals("UPDATE")?SchedulerManagement.previous(owner(s),existing):null,Set.of("RUN","ENABLE").contains(d.action())?existing.get("JOB_ACTION"):d.code());
        pending.put(s,new Pending(p,Instant.now().plusSeconds(300)));return p;
    });}}
    public Map<String,Object> apply(PoolSession s,Apply input){synchronized(s){
        Pending pendingPlan=pending.remove(s);SchedulerManagement.require(input!=null&&pendingPlan!=null&&pendingPlan.expires().isAfter(Instant.now())&&pendingPlan.preview().token().equals(input.token()),"expired");
        Preview p=pendingPlan.preview();SchedulerManagement.require(input.consent()&&p.confirmation().equals(input.confirmation()),"confirm");
        return run(s,()->{State before=repository.state();SchedulerManagement.require(p.revision().equals(OrdsManagement.digest(before))&&p.sql().equals(SchedulerManagement.sql(p.draft(),before,owner(s))),"stale");
            repository.execute(p.sql());State after=repository.state();SchedulerManagement.verify(p.draft(),after);
            return Map.of("status",p.draft().action().equals("RUN")?"SUBMITTED":"VERIFIED");
        });
    }}
}
