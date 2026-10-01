package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.OrdsApiTest.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.dbcompanion.service.OrdsApiRoute.require;

@Service
public class OrdsApiTestService {
    private record Pending(String token,Selection selection,URI base,OrdsApiRoute.Route route,Instant expires) {}
    private final Map<PoolSession,Pending> pending=Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<PoolSession> running=Collections.newSetFromMap(new WeakHashMap<>());
    private final OrdsManagementService management;private final OrdsApiTargets targets;private final OrdsApiTransport transport;
    public OrdsApiTestService(OrdsManagementService management,OrdsApiTargets targets,OrdsApiTransport transport){this.management=management;this.targets=targets;this.transport=transport;}
    public Prepared prepare(PoolSession session,Selection selection){
        synchronized(session){
            pending.remove(session);require(selection!=null&&selection.schema()!=null,"test.requestInvalid");
            var snapshot=management.list(session,selection.schema());var base=targets.resolve(session.metadata().info().database());var route=OrdsApiRoute.resolve(selection,snapshot);
            var next=new Pending(UUID.randomUUID().toString(),selection,base,route,Instant.now().plusSeconds(300));pending.put(session,next);
            return new Prepared(next.token(),route.method(),base.toString(),route.path(),route.parameters(),next.expires().toString(),30,OrdsApiTransport.RESPONSE_LIMIT);
        }
    }
    public Result run(PoolSession session,Request input){
        Pending saved;URI uri;
        synchronized(session){
            saved=pending.remove(session);require(input!=null&&saved!=null&&Objects.equals(input.token(),saved.token())&&saved.expires().isAfter(Instant.now()),"test.expired");
            synchronized(running){require(running.add(session),"test.busy");}
            try{
                var current=management.list(session,saved.selection().schema());OrdsApiRoute.resolve(saved.selection(),current);
                uri=OrdsApiRoute.uri(saved.base(),saved.route(),input.parameters(),input.query());OrdsApiTransport.body(saved.route().method(),input);OrdsApiTransport.headers(input);
            }catch(RuntimeException ex){synchronized(running){running.remove(session);}throw ex;}
        }
        try{return transport.execute(uri,saved.route().method(),input);}finally{synchronized(running){running.remove(session);}}
    }
}
