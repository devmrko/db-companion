package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.SemanticChange;
import com.dbcompanion.model.SemanticChange.Result;
import com.dbcompanion.repository.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A committed request survives native DDL and an uncertain follow-up. Caller binds its owner. */
@Service
public class SemanticChangeService {
    private final AppRecordRepository records;
    private final SemanticChangeRepository history;
    private final SessionDataSource source;
    private final JsonMapper json;
    private final TransactionTemplate write;
    public SemanticChangeService(SessionDataSource source,AppRecordRepository records,SemanticChangeRepository history,JsonMapper json){
        this.source=source;this.records=records;this.history=history;this.json=json;
        write=new TransactionTemplate(new DataSourceTransactionManager(source));write.setTimeout(20);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public <T> Result change(String owner,String kind,String name,String operation,Object before,Object request,
                              Runnable execute,Supplier<T> readback,Predicate<T> matches){
        SemanticChange.target(kind,name);
        String id=UUID.randomUUID().toString();
        var payload=new LinkedHashMap<String,Object>();
        payload.put("kind",kind);payload.put("name",name);payload.put("operation",operation);
        payload.put("before",before);payload.put("request",request);
        write.executeWithoutResult(tx->{if(!records.status(owner,owner).equals("READY"))throw SemanticChange.error(409,"missing");records.begin(owner,id,SemanticChange.TYPE,json.valueToTree(payload));});
        boolean verified=false;
        try{execute.run();}catch(RuntimeException ex){payload.put("executionCode",CredentialCatalogRepository.error(ex));}
        try{T after=readback.get();payload.put("after",after);verified=!payload.containsKey("executionCode")&&matches.test(after);}
        catch(RuntimeException ex){payload.put("readbackCode",CredentialCatalogRepository.error(ex));}
        payload.put("verified",verified);
        String state=verified?"SUCCEEDED":"CHECK_REQUIRED";
        try{write.executeWithoutResult(tx->{if(!records.finish(owner,id,state,json.valueToTree(payload)))throw SemanticChange.error(409,"pending");});}
        catch(RuntimeException ex){throw SemanticChange.error(503,"pending");}
        return new Result(id,verified,com.dbcompanion.common.i18n.UiMessages.text(verified?"semanticHistory.verified":"semanticHistory.uncertain",verified?"Changed and verified; history saved.":"Check the current database state and history; no automatic retry."));
    }
    private String owner(PoolSession session,String schema,String kind){
        SemanticChange.target(kind,"HISTORY");
        if(!session.metadata().info().username().equals(schema)
            ||(!kind.equals("CALLBACK")&&!session.metadata().selectedSchema().equals(schema)))throw SemanticChange.error(403,"owner");
        return schema;
    }
    private <T>T bound(PoolSession session,String schema,String kind,Supplier<T> work){
        synchronized(session){owner(session,schema,kind);source.bind(session.pool(),schema);try{return work.get();}finally{source.clear();}}
    }
    public Map<String,String> status(PoolSession session,String schema,String kind){return bound(session,schema,kind,()->{
        var state=records.status(schema,schema);return Map.of("storage",state,"sql",state.equals("MISSING")?AppRecordSql.create(schema):"");
    });}
    public Map<String,String> install(PoolSession session,String schema,String kind,boolean consent){
        if(!consent)throw SemanticChange.error(400,"confirm");
        return bound(session,schema,kind,()->{write.executeWithoutResult(tx->records.install(schema,schema));return Map.of("storage",records.status(schema,schema));});
    }
    private void requireRecords(String schema){if(!records.status(schema,schema).equals("READY"))throw SemanticChange.error(409,"missing");}
    public Object page(PoolSession session,String schema,String kind,String name){return bound(session,schema,kind,()->{requireRecords(schema);return history.page(schema,kind,name);});}
    public Object detail(PoolSession session,String schema,String kind,String id){return bound(session,schema,kind,()->{
        requireRecords(schema);var entry=records.detail(schema,id,SemanticChange.TYPE);
        if(entry==null||!entry.payload().path("kind").asString().equals(kind))throw SemanticChange.error(404,"missingEntry");return entry;
    });}
}
