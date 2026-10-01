package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.VpdManagement;
import com.dbcompanion.model.VpdManagement.*;
import com.dbcompanion.repository.DeepDataSecurityRepository;
import com.dbcompanion.repository.VpdManagementRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class VpdManagementService {
    private final SessionDataSource source;
    private final VpdManagementRepository repository;
    private final Map<PoolSession,Pending> pending=Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<PoolSession> blocked=Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    public VpdManagementService(SessionDataSource source,VpdManagementRepository repository){this.source=source;this.repository=repository;}
    private <T>T query(PoolSession session,Supplier<T> work){
        source.bind(session.pool(),session.metadata().info().username());
        try{return work.get();}finally{source.clear();}
    }
    private static void scope(PoolSession session,String schema){
        if(!Objects.equals(schema,session.metadata().selectedSchema()))throw new Failure(409,"vpd.stale");
    }
    private void owner(PoolSession session,String schema){
        scope(session,schema);
        if(!schema.equals(session.metadata().info().username()))throw new Failure(403,"vpd.ownerOnly");
        if(blocked.contains(session))throw new Failure(409,"vpd.blocked");
    }
    public List<String> tables(PoolSession session,String schema){
        synchronized(session){scope(session,schema);return query(session,()->repository.tables(schema));}
    }
    public List<Target> objects(PoolSession session,String schema){
        synchronized(session){scope(session,schema);return query(session,()->repository.objects(schema));}
    }
    public List<Function> functions(PoolSession session,String schema,String functionSchema){
        synchronized(session){scope(session,schema);VpdManagement.identifier(functionSchema);return query(session,()->repository.functions(functionSchema));}
    }
    public Catalog list(PoolSession session,String schema,String table){
        synchronized(session){
            scope(session,schema);
            return query(session,()->{
                try{
                    var snapshot=repository.snapshot(schema,table);String reason="";
                    if(!schema.equals(session.metadata().info().username()))reason="vpd.ownerOnly";
                    else if(blocked.contains(session))reason="vpd.blocked";
                    else if(!repository.canManage())reason="vpd.executeRequired";
                    return new Catalog("AVAILABLE","",VpdManagementRepository.policies(snapshot),
                        snapshot.columns().stream().map(r->r.get("COLUMN_NAME")).toList(),reason.isEmpty(),reason,snapshot.fingerprint());
                }catch(Failure ex){throw ex;}
                catch(RuntimeException ex){return new Catalog(DeepDataSecurityRepository.accessError(ex)?"ACCESS_REQUIRED":"ERROR",
                    "vpd.readError",List.of(),List.of(),false,"vpd.readError","");}
            });
        }
    }
    public Preview preview(PoolSession session,Action action,Draft request,String observedFingerprint){
        synchronized(session){
            pending.remove(session);
            if(action==null||request==null)throw new IllegalArgumentException("action");
            owner(session,request.schema());VpdManagement.identifier(request.schema());VpdManagement.identifier(request.table());VpdManagement.identifier(request.policy());
            var prepared=query(session,()->{
                if(!repository.canManage())throw new Failure(403,"vpd.executeRequired");
                var snapshot=repository.snapshot(request.schema(),request.table());
                if(!snapshot.fingerprint().equals(observedFingerprint))throw new Failure(409,"vpd.stale");
                var original=find(snapshot,request.policy());Draft desired=request;
                if(action==Action.ADD){if(original!=null)throw new Failure(409,"vpd.duplicate");}
                else{
                    if(original==null)throw new Failure(409,"vpd.stale");
                    if(!original.editable())throw new Failure(409,"vpd.unsupported");
                    if(action!=Action.REPLACE)desired=original.definition();
                }
                desired=desired.validated();
                if(!snapshot.columns().stream().map(r->r.get("COLUMN_NAME")).toList().containsAll(desired.columns()))throw new IllegalArgumentException("columns");
                String functionIdentity="";
                if(action==Action.ADD||action==Action.REPLACE)functionIdentity=function(desired).identity();
                List<Command> commands=switch(action){
                    case ADD -> List.of(VpdManagementRepository.add(desired));
                    case REPLACE -> List.of(VpdManagementRepository.drop(original.definition()),VpdManagementRepository.add(desired));
                    case DELETE -> List.of(VpdManagementRepository.drop(desired));
                    case ENABLE,DISABLE -> List.of(VpdManagementRepository.enable(desired,action==Action.ENABLE));
                };
                String recovery=switch(action){
                    case ADD -> VpdManagementRepository.drop(desired).preview();
                    case REPLACE,DELETE -> VpdManagementRepository.add(original.definition()).preview();
                    case ENABLE,DISABLE -> VpdManagementRepository.enable(desired,desired.enabled()).preview();
                };
                Instant expires=Instant.now().plusSeconds(300);
                var view=new Preview(UUID.randomUUID().toString(),action,desired.schema()+"."+desired.table()+"."+desired.policy(),
                    commands.stream().map(Command::preview).collect(Collectors.joining("\n\n")),recovery,action==Action.REPLACE,expires.toString());
                return new Pending(view,desired,snapshot.fingerprint(),functionIdentity,expires,commands);
            });
            pending.put(session,prepared);return prepared.preview();
        }
    }
    private Function function(Draft draft){
        return repository.functions(draft.functionSchema()).stream().filter(f->f.name().equals(draft.function())).findFirst()
            .orElseThrow(()->new Failure(409,"vpd.functionRequired"));
    }
    private static Policy find(Snapshot snapshot,String name){
        // A duplicate name in another group cannot accidentally be addressed by DROP_POLICY.
        var matches=VpdManagementRepository.policies(snapshot).stream().filter(p->p.name().equals(name)).toList();
        if(matches.size()>1)throw new Failure(409,"vpd.unsupported");
        return matches.isEmpty()?null:matches.getFirst();
    }
    public Receipt execute(PoolSession session,String token,String target,boolean confirmed,boolean gapConfirmed){
        synchronized(session){
            var prepared=pending.remove(session);
            validateConfirmation(prepared,token,target,confirmed,gapConfirmed,Instant.now());
            owner(session,prepared.draft().schema());
            return query(session,()->{
                if(!repository.canManage())throw new Failure(403,"vpd.executeRequired");
                var draft=prepared.draft();var before=repository.snapshot(draft.schema(),draft.table());
                if(!prepared.fingerprint().equals(before.fingerprint()))throw new Failure(409,"vpd.stale");
                if(!prepared.functionIdentity().isEmpty()&&!prepared.functionIdentity().equals(function(draft).identity()))throw new Failure(409,"vpd.stale");
                // Oracle commits these procedures. Never wrap replacement in a purported rollback guarantee.
                try{
                    repository.execute(prepared.commands());
                    var after=repository.snapshot(draft.schema(),draft.table());
                    if(!verified(prepared,after))return uncertain(session,prepared);
                }catch(RuntimeException ex){return uncertain(session,prepared);}
                return new Receipt("VERIFIED","vpd.success",prepared.preview().recoverySql());
            });
        }
    }
    public static void validateConfirmation(Pending prepared,String token,String target,boolean confirmed,boolean gapConfirmed,Instant now){
        if(prepared==null||!prepared.preview().token().equals(token)||!now.isBefore(prepared.expiresAt()))throw new Failure(409,"vpd.stale");
        if(!confirmed||!prepared.preview().target().equals(target)||(prepared.preview().gap()&&!gapConfirmed))throw new Failure(400,"vpd.confirmRequired");
    }
    private Receipt uncertain(PoolSession session,Pending prepared){
        blocked.add(session);return new Receipt("CHECK_REQUIRED","vpd.uncertain",prepared.preview().recoverySql());
    }
    public static boolean verified(Pending pending,Snapshot snapshot){
        var actual=find(snapshot,pending.draft().policy());
        return switch(pending.preview().action()){
            case DELETE -> actual==null;
            case ADD,REPLACE -> actual!=null&&actual.editable()&&pending.draft().equals(actual.definition());
            case ENABLE,DISABLE -> actual!=null&&actual.editable()&&pending.draft().withEnabled(pending.preview().action()==Action.ENABLE).equals(actual.definition());
        };
    }
}
