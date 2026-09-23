package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.*;
import com.dbcompanion.model.QueryArchive.*;
import com.dbcompanion.repository.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OntologyArchiveService {
    private final SessionDataSource source;private final AppRecordRepository records;private final NativeRdfRepository rdf;private final OntologyRepository catalog;private final JsonMapper json;
    private final TransactionTemplate read,write;
    public OntologyArchiveService(SessionDataSource source,AppRecordRepository records,NativeRdfRepository rdf,OntologyRepository catalog,JsonMapper json){
        this.source=source;this.records=records;this.rdf=rdf;this.catalog=catalog;this.json=json;
        var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(60);write=new TransactionTemplate(manager);write.setTimeout(120);
    }
    private String login(PoolSession s){return s.metadata().info().username();}
    private void scope(PoolSession s,String schema){Ontology.name(schema);if(!schema.equals(s.metadata().selectedSchema()))throw new Ontology.Failure(409,"query.stale");}
    private <T>T tx(PoolSession s,TransactionTemplate template,Supplier<T> action){source.bind(s.pool(),login(s));try{return template.execute(status->action.get());}finally{source.clear();}}
    private Status inspect(PoolSession s,String schema){
        String recordState=records.status(schema,login(s)),nativeState="MISSING",diagnostic="";boolean api=rdf.api();String tablespace=schema.equals(login(s))?rdf.tablespace():"";
        if(recordState.equals("READY")){
            var store=records.detail(schema,QueryArchive.STORE_ID,QueryArchive.STORE);
            if(store==null){if(rdf.exists(schema))nativeState="MISMATCH";}
            else if(!store.item().state().equals("SUCCEEDED")){nativeState="CHECK_REQUIRED";diagnostic=store.payload().path("error").asString("");}
            else try{validateStore(schema,store);rdf.probe(schema);nativeState="READY";}catch(RuntimeException ex){nativeState="UNAVAILABLE";diagnostic=detail(ex);}
        }
        if(!api&&nativeState.equals("MISSING"))nativeState="UNSUPPORTED";
        return new Status(recordState,nativeState,schema.equals(login(s)),api,tablespace,Instant.now().toString(),diagnostic);
    }
    public Status status(PoolSession s,String schema,boolean refresh){synchronized(s){scope(s,schema);return s.metadata().queryArchive().status(schema,refresh,()->tx(s,read,()->inspect(s,schema)));}}
    public Status installRecords(PoolSession s,String schema,boolean confirmed){synchronized(s){
        scope(s,schema);confirm(confirmed);QueryArchive.owner(schema,login(s));
        try{tx(s,write,()->{records.install(schema,login(s));return true;});}catch(RuntimeException ex){throw new OperationFailure("records",null,ex);}
        finally{s.metadata().queryArchive().forget(schema);}return status(s,schema,true);
    }}
    private void confirm(boolean value){if(!value)throw new Ontology.Failure(400,"archive.confirm");}
    public Map<String,String> setupPreview(PoolSession s,String schema){synchronized(s){scope(s,schema);QueryArchive.owner(schema,login(s));var v=status(s,schema,true);if(!v.records().equals("READY")||!v.rdf().equals("MISSING")||!v.api())throw new Ontology.Failure(409,"archive.notReady");return Map.of("schema",schema,"tablespace",v.tablespace(),"sql",RdfQuerySql.setupPreview(schema,v.tablespace()));}}
    public Status installRdf(PoolSession s,String schema,String tablespace,boolean confirmed){synchronized(s){
        scope(s,schema);confirm(confirmed);QueryArchive.owner(schema,login(s));var state=status(s,schema,true);
        if(!state.records().equals("READY")||!state.rdf().equals("MISSING")||!state.api()||!state.tablespace().equals(tablespace))throw new Ontology.Failure(409,"archive.notReady");
        var payload=json.createObjectNode().put("format",1).put("schema",schema).put("network",RdfQuerySql.NETWORK).put("model",RdfQuerySql.MODEL).put("tablespace",tablespace);
        // This unique record is a durable installation claim. No resumed/automatic DDL after a partial install.
        try{tx(s,write,()->{records.require(schema,login(s));records.begin(schema,QueryArchive.STORE_ID,QueryArchive.STORE,payload);return true;});}
        catch(RuntimeException ex){s.metadata().queryArchive().forget(schema);throw new OperationFailure("begin",QueryArchive.STORE_ID,ex);}
        try{tx(s,write,()->{rdf.create(schema,tablespace);payload.put("modelId",rdf.modelId(schema));if(!records.finish(schema,QueryArchive.STORE_ID,"SUCCEEDED",payload))throw new Ontology.Failure(409,"archive.verifyFailed");return true;});}
        catch(RuntimeException ex){recordFailure(s,schema,QueryArchive.STORE_ID,payload,"setup",ex);throw new OperationFailure("setup",QueryArchive.STORE_ID,ex);}
        finally{s.metadata().queryArchive().forget(schema);}return status(s,schema,true);
    }}
    private String validateStore(String schema,Detail store){
        var p=store.payload();if(!store.item().state().equals("SUCCEEDED")||p.path("format").asInt()!=1||!p.path("schema").asString().equals(schema)||!p.path("network").asString().equals(RdfQuerySql.NETWORK)||!p.path("model").asString().equals(RdfQuerySql.MODEL))throw new Ontology.Failure(409,"archive.rdfMismatch");
        var id=rdf.modelId(schema);if(!id.equals(p.path("modelId").asString()))throw new Ontology.Failure(409,"archive.rdfMismatch");return id;
    }
    private String require(PoolSession s,String schema){records.require(schema,login(s));var store=records.detail(schema,QueryArchive.STORE_ID,QueryArchive.STORE);if(store==null)throw new Ontology.Failure(409,"archive.notReady");return validateStore(schema,store);}
    private void verify(PoolSession s,String schema,List<Ontology.Entry> entries){
        catalog.require(schema,login(s));for(var e:entries){var current=catalog.entry(schema,e.document().source().table(),0);if(current==null||!e.state().equals(current.state())||!PropertyGraph.sameDefinitions(List.of(e),List.of(current)))throw new Ontology.Failure(409,"query.stale");}
    }
    public Preview preview(PoolSession s,String searchId,String route){synchronized(s){
        String schema=s.metadata().selectedSchema();QueryArchive.owner(schema,login(s));var state=s.metadata().inquiry();
        var selected=OntologyInquiry.chooseRoute(state.search(searchId,schema),route);var entries=OntologyInquiry.selected(selected,state.data());
        return tx(s,read,()->{
            var modelId=require(s,schema);verify(s,schema,entries);var model=QueryArchiveRdf.evidence(selected,entries,json);
            String id=UUID.randomUUID().toString(),query=RdfQuerySql.construct(id,false);var info=s.metadata().info();
            var locator=new Locator(info.database(),info.service(),schema,RdfQuerySql.NETWORK,RdfQuerySql.MODEL,modelId,QueryArchive.graph(id,true));
            var payload=json.createObjectNode().put("format",1).put("kind",QueryArchive.TYPE).put("id",id).put("question",selected.question()).put("mode","EVIDENCE_SUBGRAPH").put("preparedAt",Instant.now().toString()).put("sparql",query).put("sourceGraph",QueryArchive.graph(id,false)).put("expectedCount",model.size()).put("expectedHash",QueryArchiveRdf.hash(model));
            payload.set("graph",json.valueToTree(locator));payload.set("context",json.readTree(OntologyInquiry.payload(selected,entries,json)));
            var outcome=state.outcome(route);
            if(outcome!=null&&outcome.answer()!=null)payload.set("answer",json.valueToTree(outcome.answer()));
            if(outcome!=null&&outcome.sql()!=null){var v=outcome.sql();payload.set("sql",json.valueToTree(Map.of("text",v.sql(),"hash",v.hash(),"profile",v.profile(),"generatedAt",v.generatedAt(),"validated",v.executable(),"reason",v.reason())));}
            if(json.writeValueAsString(payload).length()>QueryArchive.MAX_JSON)throw new Ontology.Failure(413,"archive.limit");
            var preview=new Preview(id,UUID.randomUUID().toString(),schema,selected.question(),query,QueryArchiveRdf.text(model),payload,model.size(),Instant.now().plusSeconds(600));
            s.metadata().queryArchive().prepare(new Pending(preview,selected,entries));return preview;
        });
    }}
    public Detail save(PoolSession s,String token,boolean confirmed){synchronized(s){
        String schema=s.metadata().selectedSchema();QueryArchive.owner(schema,login(s));var pending=s.metadata().queryArchive().consume(token,schema,confirmed,Instant.now());var p=pending.preview();
        s.metadata().inquiry().search(pending.search().id(),schema);var payload=(ObjectNode)p.payload().deepCopy();
        try{tx(s,write,()->{var modelId=require(s,schema);if(!payload.path("graph").path("modelId").asString().equals(modelId))throw new Ontology.Failure(409,"archive.rdfMismatch");verify(s,schema,pending.entries());records.begin(schema,p.id(),QueryArchive.TYPE,payload);return true;});}
        catch(RuntimeException ex){throw new OperationFailure("begin",p.id(),ex);}
        String[] stage={"verify"};
        try{tx(s,write,()->{
            require(s,schema);verify(s,schema,pending.entries());var expected=QueryArchiveRdf.parse(p.turtle());
            stage[0]="sourceInsert";rdf.insert(schema,p.id(),false,expected);stage[0]="construct";var result=rdf.construct(schema,p.id(),false);QueryArchiveRdf.same(expected,result);
            stage[0]="resultInsert";rdf.insert(schema,p.id(),true,result);stage[0]="resultVerify";var stored=rdf.construct(schema,p.id(),true);QueryArchiveRdf.same(result,stored);
            payload.put("resultCount",stored.size()).put("resultHash",QueryArchiveRdf.hash(stored)).put("completedAt",Instant.now().toString());
            stage[0]="commit";if(!records.finish(schema,p.id(),"SUCCEEDED",payload))throw new Ontology.Failure(409,"archive.verifyFailed");return true;
        });}catch(RuntimeException ex){recordFailure(s,schema,p.id(),payload,stage[0],ex);throw new OperationFailure(stage[0],p.id(),ex);}
        // A failed read-back does not rewrite a successful commit or replay the writes.
        try{return tx(s,read,()->records.detail(schema,p.id(),QueryArchive.TYPE));}catch(RuntimeException ex){throw new OperationFailure("readBack",p.id(),ex);}
    }}
    private void recordFailure(PoolSession s,String schema,String id,ObjectNode original,String stage,RuntimeException cause){
        var payload=original.deepCopy();payload.remove("resultCount");payload.remove("resultHash");payload.remove("completedAt");payload.put("stage",stage).put("error",detail(cause)).put("failedAt",Instant.now().toString());
        try{tx(s,write,()->records.finish(schema,id,"CHECK_REQUIRED",payload));}catch(RuntimeException secondary){cause.addSuppressed(secondary);}
    }
    public Page page(PoolSession s,String schema,String before){synchronized(s){scope(s,schema);return tx(s,read,()->{records.require(schema,login(s));return records.page(schema,before);});}}
    public Detail detail(PoolSession s,String schema,String id){synchronized(s){scope(s,schema);return tx(s,read,()->{records.require(schema,login(s));var value=records.detail(schema,id,QueryArchive.TYPE);if(value==null)throw new Ontology.Failure(404,"archive.notFound");return value;});}}
    public Graph graph(PoolSession s,String schema,String id){synchronized(s){scope(s,schema);return tx(s,read,()->{
        String modelId=require(s,schema);var record=records.detail(schema,id,QueryArchive.TYPE);if(record==null||!record.item().state().equals("SUCCEEDED"))throw new Ontology.Failure(409,"archive.notReady");
        var p=record.payload();var locator=p.path("graph");if(!locator.path("schema").asString().equals(schema)||!locator.path("network").asString().equals(RdfQuerySql.NETWORK)||!locator.path("model").asString().equals(RdfQuerySql.MODEL)||!locator.path("modelId").asString().equals(modelId)||!locator.path("namedGraph").asString().equals(QueryArchive.graph(id,true)))throw new Ontology.Failure(409,"archive.rdfMismatch");
        var model=rdf.construct(schema,id,true);var hash=QueryArchiveRdf.hash(model);if(model.size()!=p.path("resultCount").asInt()||!hash.equals(p.path("resultHash").asString()))throw new Ontology.Failure(409,"archive.verifyFailed");return new Graph(id,model.size(),hash,QueryArchiveRdf.text(model));
    });}}
    public static String detail(Throwable ex){String stage=ex instanceof NativeRdfRepository.StageFailure v?v.operation()+" · ":"";for(Throwable c=ex;c!=null;c=c.getCause())if(c instanceof SQLException sql)return stage+"Oracle code="+sql.getErrorCode()+" · "+sql.getMessage();return Objects.toString(ex.getMessage(),ex.getClass().getSimpleName());}
    public static final class OperationFailure extends RuntimeException {
        public OperationFailure(String stage,String id,RuntimeException cause){super(UiMessages.text("ontology.archive.operation."+stage,stage)+" · "+(id==null?"":id+" · ")+detail(cause)+" · "+UiMessages.text("ontology.archive.checkRequired","Check state; no automatic retry."),cause);}
    }
}
