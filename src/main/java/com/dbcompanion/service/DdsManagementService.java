package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.DdsManagement;
import com.dbcompanion.model.DdsManagement.*;
import com.dbcompanion.model.OrdsManagement;
import com.dbcompanion.model.DdsIdentity;
import com.dbcompanion.repository.DdsManagementRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class DdsManagementService {
    private record Pending(Preview preview,Instant expires) {}
    private final Map<PoolSession,Pending> pending=Collections.synchronizedMap(new WeakHashMap<>());
    private record IdentityPending(DdsIdentity.Preview preview,Instant expires) {}
    private final Map<PoolSession,IdentityPending> identityPending=Collections.synchronizedMap(new WeakHashMap<>());
    private final SessionDataSource source;
    private final DdsManagementRepository repository;
    public DdsManagementService(SessionDataSource source,DdsManagementRepository repository){this.source=source;this.repository=repository;}
    private String owner(PoolSession s){String user=s.metadata().info().username();DdsManagement.require(user.equals(s.metadata().selectedSchema()),"ownSchema");return user;}
    private <T>T run(PoolSession s,Supplier<T> work){source.bind(s.pool(),owner(s));try{return work.get();}finally{source.clear();}}
    public State state(PoolSession s){synchronized(s){return run(s,()->repository.state(owner(s)));}}
    private void identityAdmin(PoolSession s){DdsManagement.require("ADMIN".equals(owner(s)),"adminRole");}
    public DdsIdentity.State identities(PoolSession s){synchronized(s){identityAdmin(s);return run(s,repository::identities);}}
    public String memoryProbeTable(PoolSession s,String store){synchronized(s){identityAdmin(s);return run(s,()->repository.memoryProbeTable(store));}}
    public Map<String,String> runtimeReadiness(PoolSession s){synchronized(s){identityAdmin(s);return run(s,repository::runtimeReadiness);}}
    public DdsIdentity.Preview identityPreview(PoolSession s,DdsIdentity.Request request){synchronized(s){
        identityPending.remove(s);identityAdmin(s);
        return run(s,()->{
            var state=repository.identities();DdsManagement.require(request!=null&&Objects.equals(request.revision(),OrdsManagement.digest(state)),"stale");
            var d=request.draft();String sql=DdsIdentity.sql(d,state);
            var plan=new DdsIdentity.Preview(UUID.randomUUID().toString(),d.action()+" END USER "+d.username()+
                    (d.action().equals("CREATE")?"":" / "+d.role()),sql,request.revision(),d);
            identityPending.put(s,new IdentityPending(plan,Instant.now().plusSeconds(300)));return plan;
        });
    }}
    public Map<String,Object> identityApply(PoolSession s,DdsIdentity.Apply input){synchronized(s){
        var pendingPlan=identityPending.remove(s);identityAdmin(s);
        DdsManagement.require(input!=null&&pendingPlan!=null&&pendingPlan.expires().isAfter(Instant.now())
                &&pendingPlan.preview().token().equals(input.token()),"expired");
        var plan=pendingPlan.preview();DdsManagement.require(input.consent()&&plan.confirmation().equals(input.confirmation()),"confirm");
        return run(s,()->{
            var before=repository.identities();DdsManagement.require(plan.revision().equals(OrdsManagement.digest(before)),"stale");
            DdsManagement.require(plan.sql().equals(DdsIdentity.sql(plan.draft(),before)),"stale");
            repository.execute(DdsIdentity.executable(plan.draft(),before,input.password()));
            var after=repository.identities();DdsIdentity.verify(plan.draft(),after);
            return Map.of("status","VERIFIED","state",after);
        });
    }}
    public Preview preview(PoolSession s,PreviewRequest request){synchronized(s){
        pending.remove(s);return run(s,()->{
            State state=repository.state(owner(s));DdsManagement.require(request!=null&&Objects.equals(request.revision(),OrdsManagement.digest(state)),"stale");
            Draft draft=request.draft();String sql=DdsManagement.sql(draft,state,owner(s));
            var result=new Preview(UUID.randomUUID().toString(),draft.action()+" "+draft.schema()+" / "+draft.kind()+" / "+draft.name(),sql,OrdsManagement.digest(state),draft,draft.action().equals("UPDATE")?DdsManagement.previous(draft,state):null);
            pending.put(s,new Pending(result,Instant.now().plusSeconds(300)));return result;
        });
    }}
    public Map<String,Object> apply(PoolSession s,Apply input){synchronized(s){
        Pending p=pending.remove(s);DdsManagement.require(input!=null&&p!=null&&p.expires().isAfter(Instant.now())&&p.preview().token().equals(input.token()),"expired");
        DdsManagement.require(input.consent()&&p.preview().confirmation().equals(input.confirmation()),"confirm");
        return run(s,()->{
            State before=repository.state(owner(s));Preview plan=p.preview();
            DdsManagement.require(OrdsManagement.digest(before).equals(plan.revision()),"stale");
            String sql=DdsManagement.sql(plan.draft(),before,owner(s));DdsManagement.require(sql.equals(plan.sql()),"stale");
            // Oracle DDL commits implicitly. Never retry or claim rollback on an uncertain result.
            repository.execute(sql);State after=repository.state(owner(s));DdsManagement.verify(plan.draft(),after);
            return Map.of("status","VERIFIED","state",after);
        });
    }}
}
