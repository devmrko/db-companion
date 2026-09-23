package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.FeedbackEditing.*;
import com.dbcompanion.repository.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class FeedbackEditingService {
    private final SessionDataSource source;private final AiCreationRepository repository;private final AiFeedbackRepository feedback;private final AiHistoryRepository history;private final JsonMapper json;private final TransactionTemplate read,write;
    public FeedbackEditingService(SessionDataSource source,AiCreationRepository repository,AiFeedbackRepository feedback,AiHistoryRepository history,JsonMapper json){this.source=source;this.repository=repository;this.feedback=feedback;this.history=history;this.json=json;var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(15);write=new TransactionTemplate(manager);write.setTimeout(65);}
    private <T>T bound(PoolSession s,String schema,Supplier<T> fn){synchronized(s){AiCreation.scope(s.metadata().info().username(),s.metadata().selectedSchema(),schema);source.bind(s.pool(),schema);try{return fn.get();}finally{source.clear();}}}
    private AiCreation.Feedback row(String schema,String profile,String id){var detail=feedback.detail(new AiFeedback.Query(schema,profile,"","",1),id);if(detail==null)throw FeedbackEditing.error("changed");var row=new AiCreation.Feedback(detail.question(),detail.attributes());AiCreation.feedback(row,json);return row;}
    public Form form(PoolSession s,String schema,String profile,String id){
        AiFeedback.tableName(profile);if(id!=null&&!id.isEmpty())AiFeedback.rowId(id);
        return bound(s,schema,()->read.execute(tx->{
            var snapshot=repository.snapshot(schema,AiCreation.Kind.PROFILE,profile);if(!Boolean.TRUE.equals(snapshot.get("exists")))throw AiCreation.error(404,"sourceMissing");
            var before=id==null||id.isEmpty()?null:row(schema,profile,id);var plan=new Plan(schema,profile,id,snapshot,before);s.metadata().feedbackEditing().set(plan);
            var n=before==null?null:AiCreation.feedback(before,json);
            return new Form(plan.token,n==null?"select ai showsql ":n.get("sql_text").stringValue(),n==null?"":n.get("response").stringValue(),n==null?"":n.path("feedback_content").asString(""),before!=null,history.ready(schema));
        }));
    }
    private void unchanged(Plan p,String key){
        if(!AiCreation.fingerprint(p.snapshot,json).equals(AiCreation.fingerprint(repository.snapshot(p.schema,AiCreation.Kind.PROFILE,p.profile),json)))throw AiCreation.error(409,"sourceChanged");
        var current=repository.feedbackKey(p.schema,p.profile,key);
        if(p.before==null){if(!current.isEmpty())throw FeedbackEditing.error("exists");}
        else if(!AiCreation.feedbackMatches(List.of(p.before),current,json)||!AiCreation.feedbackMatches(List.of(p.before),List.of(row(p.schema,p.profile,p.id)),json))throw FeedbackEditing.error("changed");
    }
    public synchronized Result save(PoolSession s,Save request){
        var wanted=FeedbackEditing.desired(request,json);
        AiCreation.feedback(wanted,json);
        synchronized(s){var p=s.metadata().feedbackEditing().take(request.token());return bound(s,p.schema,()->{
            if(p.before!=null&&!AiCreation.feedback(p.before,json).get("sql_text").stringValue().equals(request.sqlText()))throw FeedbackEditing.error("fixedKey");
            var desired=p.before==null?wanted:new AiCreation.Feedback(p.before.question(),wanted.attributes());
            return saveBound(s,p,request,desired);
        });}
    }
    private Result saveBound(PoolSession s,Plan p,Save request,AiCreation.Feedback desired){
        String owner=read.execute(tx->{history.require(p.schema);unchanged(p,request.sqlText());return repository.feedbackOwner(p.schema);});
        String action=p.before==null?"FEEDBACK_ADD":"FEEDBACK_REPLACE";
        var data=new LinkedHashMap<String,Object>(p.snapshot);data.put("feedback",p.before==null?List.of():List.of(p.before));data.put("requestedFeedback",desired);
        String key=write.execute(tx->history.before(p.schema,"PROFILE",p.profile,Objects.toString(((Map<?,?>)p.snapshot.get("profile")).get("PROFILE_ID"),null),action,s.metadata().info().username(),json.writeValueAsString(data)));
        String stage="preflight";boolean called=false;
        try{
            read.executeWithoutResult(tx->unchanged(p,request.sqlText()));stage="FEEDBACK";called=true;write.executeWithoutResult(tx->repository.addFeedback(owner,p.profile,desired,json));
            stage="readback";var actual=read.execute(tx->repository.feedbackKey(p.schema,p.profile,request.sqlText()));boolean matches=AiCreation.feedbackMatches(List.of(desired),actual,json);
            var after=new LinkedHashMap<String,Object>(p.snapshot);after.put("feedback",actual);stage="history";write.executeWithoutResult(tx->history.after(p.schema,"PROFILE",p.profile,key,matches?"VERIFIED":"UNCERTAIN",json.writeValueAsString(after)));
            return new Result(matches,matches?"saved":"readback",matches?"":"creation.readbackMismatch");
        }catch(RuntimeException ex){String extra="";if(called)try{var observed=new LinkedHashMap<String,Object>(p.snapshot);observed.put("feedback",read.execute(tx->repository.feedbackKey(p.schema,p.profile,request.sqlText())));observed.put("error",detail(ex));write.executeWithoutResult(tx->history.after(p.schema,"PROFILE",p.profile,key,"UNCERTAIN",json.writeValueAsString(observed)));}catch(RuntimeException follow){extra="\ncreation.followupFailed\n"+detail(follow);}return new Result(false,stage,(called?"creation.partial":"creation.notCalled")+"\n"+detail(ex)+extra);}
    }
    private static String detail(RuntimeException ex){return ex instanceof com.dbcompanion.common.exception.MetadataEditException e?e.userMessage():CredentialCatalogRepository.error(ex);}
}
