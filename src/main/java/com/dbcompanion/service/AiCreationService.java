package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.AiCreation;
import com.dbcompanion.model.AiCreation.*;
import com.dbcompanion.repository.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AiCreationService {
    private final SessionDataSource source;private final AiCreationRepository repository;private final AiHistoryRepository history;private final JsonMapper json;
    private final TransactionTemplate read,write;
    public AiCreationService(SessionDataSource source,AiCreationRepository repository,AiHistoryRepository history,JsonMapper json){
        this.source=source;this.repository=repository;this.history=history;this.json=json;
        var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(15);write=new TransactionTemplate(manager);write.setTimeout(65);
    }
    private <T>T bound(PoolSession s,String schema,Supplier<T> action){synchronized(s){AiCreation.scope(s.metadata().info().username(),s.metadata().selectedSchema(),schema);source.bind(s.pool(),schema);try{return action.get();}finally{source.clear();}}}
    public Form form(PoolSession s,String schema,Kind kind,String original){
        if(kind==null||original==null)throw AiCreation.error(400,"attributesInvalid");
        if(!original.isEmpty())TeamEditPolicy.identifier(original);
        return bound(s,schema,()->read.execute(tx->{
            s.metadata().creation().clear();
            var snapshot=original.isEmpty()?Map.<String,Object>of("exists",false):repository.snapshot(schema,kind,original);
            if(!original.isEmpty()&&!Boolean.TRUE.equals(snapshot.get("exists")))throw AiCreation.error(404,"sourceMissing");
            String description="";if(snapshot.get(kind==Kind.PROFILE?"profile":"object") instanceof Map<?,?> fields)description=Objects.toString(fields.get("DESCRIPTION"),"");
            return new Form(AiCreation.fingerprint(snapshot,json),description,original.isEmpty()?"{}":AiCreation.attributesJson(snapshot,json),history.ready(schema),repository.credentials(),repository.references(kind));
        }));
    }
    public void install(PoolSession s,String schema){bound(s,schema,()->{try{write.executeWithoutResult(tx->history.install(schema));return null;}catch(RuntimeException ex){throw new com.dbcompanion.common.exception.MetadataEditException(503,"History preparation interrupted",com.dbcompanion.common.i18n.UiMessages.text("creation.installFailed","History preparation interrupted; inspect existing objects before retrying")+"\n"+detail(ex));}});}
    public Preview preview(PoolSession s,Input input){
        AiCreation.validate(input,json);
        return bound(s,input.schema(),()->read.execute(tx->{
            s.metadata().creation().clear();history.require(input.schema());repository.owner(input.schema(),input.kind());repository.validateReferences(input,json);
            if(Boolean.TRUE.equals(repository.snapshot(input.schema(),input.kind(),input.name()).get("exists")))throw AiCreation.error(409,"exists");
            var original=input.source().isEmpty()?Map.<String,Object>of("exists",false):repository.snapshot(input.schema(),input.kind(),input.source());
            if(!AiCreation.fingerprint(original,json).equals(input.sourceVersion()))throw AiCreation.error(409,"sourceChanged");
            if(!input.source().isEmpty()&&!Boolean.TRUE.equals(original.get("exists")))throw AiCreation.error(404,"sourceMissing");
            var rows=input.copyFeedback()?repository.feedback(input.schema(),input.source()):List.<Feedback>of();AiCreation.validateFeedback(rows,json);
            if(!rows.isEmpty())repository.feedbackOwner(input.schema());
            var plan=new Plan(input,original,rows);s.metadata().creation().put(plan);return plan.preview();
        }));
    }
    private void unchanged(Plan p){
        var in=p.input;if(!in.source().isEmpty()){
            if(!AiCreation.fingerprint(repository.snapshot(in.schema(),in.kind(),in.source()),json).equals(AiCreation.fingerprint(p.original,json)))throw AiCreation.error(409,"sourceChanged");
            if(in.copyFeedback()&&!AiCreation.feedbackMatches(p.feedback,repository.feedback(in.schema(),in.source()),json))throw AiCreation.error(409,"sourceChanged");
        }
    }
    private Map<String,Object> request(Plan p){var data=new LinkedHashMap<String,Object>();data.put("exists",false);data.put("operation","CREATE_"+p.input.kind());data.put("request",p.input);data.put("source",p.original);data.put("feedback",p.feedback);return data;}
    public synchronized Result create(PoolSession s,String token,boolean confirmed,boolean consent){
        if(!confirmed)throw AiCreation.error(400,"confirm");
        synchronized(s){var p=s.metadata().creation().get(token);return bound(s,p.input.schema(),()->{
            if(p.attempted)throw AiCreation.error(409,"used");if((p.input.kind()==Kind.PROFILE||!p.feedback.isEmpty())&&!consent)throw AiCreation.error(400,"consent");
            var in=p.input;
            String owner=read.execute(tx->{history.require(in.schema());unchanged(p);repository.validateReferences(in,json);if(Boolean.TRUE.equals(repository.snapshot(in.schema(),in.kind(),in.name()).get("exists")))throw AiCreation.error(409,"exists");return repository.owner(in.schema(),in.kind());});
            p.attempted=true;
            String key=write.execute(tx->history.before(in.schema(),in.kind().name(),in.name(),null,"CREATE_"+in.kind(),s.metadata().info().username(),json.writeValueAsString(request(p))));
            boolean called=false;String stage="preflight";
            try{
                read.executeWithoutResult(tx->{unchanged(p);if(Boolean.TRUE.equals(repository.snapshot(in.schema(),in.kind(),in.name()).get("exists")))throw AiCreation.error(409,"exists");repository.validateReferences(in,json);});
                stage="CREATE_"+in.kind();called=true;write.executeWithoutResult(tx->repository.create(owner,in));
                stage="readback";var after=read.execute(tx->repository.snapshot(in.schema(),in.kind(),in.name()));boolean matches=AiCreation.matches(in,after,json);
                stage="history";write.executeWithoutResult(tx->history.after(in.schema(),in.kind().name(),in.name(),key,matches?"VERIFIED":"UNCERTAIN",json.writeValueAsString(after)));
                if(!matches){p.blocked=true;return result(p,false,"readback","creation.readbackMismatch");}p.created=after;return result(p,true,"created","");
            }catch(RuntimeException ex){p.blocked=true;String followup=called?observeFailure(p,key,stage,ex):"";return result(p,false,stage,(called?"creation.partial":"creation.notCalled")+"\n"+detail(ex)+followup);}
        });}
    }
    public synchronized Result copyNext(PoolSession s,String token,int index,boolean consent){
        if(!consent)throw AiCreation.error(400,"consent");
        synchronized(s){var p=s.metadata().creation().get(token);return bound(s,p.input.schema(),()->{
            if(p.created==null||index!=p.copied||index>=p.feedback.size())throw AiCreation.error(409,"used");
            var in=p.input;String stage="copy-preflight";boolean called=false;String historyKey=null;
            try{
                var before=read.execute(tx->{history.require(in.schema());unchanged(p);requireCreated(p);var current=repository.feedback(in.schema(),in.name());if(!AiCreation.feedbackMatches(p.feedback.subList(0,p.copied),current,json))throw AiCreation.error(409,"targetChanged");return current;});
                String owner=read.execute(tx->repository.feedbackOwner(in.schema()));
                var data=new LinkedHashMap<String,Object>(p.created);data.put("feedback",before);data.put("copySource",in.source());data.put("copyItem",p.feedback.get(index));
                String key=write.execute(tx->history.before(in.schema(),"PROFILE",in.name(),objectId(p.created,in.kind()),"FEEDBACK_COPY",s.metadata().info().username(),json.writeValueAsString(data)));
                historyKey=key;
                read.executeWithoutResult(tx->{unchanged(p);requireCreated(p);if(!AiCreation.feedbackMatches(before,repository.feedback(in.schema(),in.name()),json))throw AiCreation.error(409,"targetChanged");});
                stage="FEEDBACK";called=true;write.executeWithoutResult(tx->repository.addFeedback(owner,in.name(),p.feedback.get(index),json));
                stage="copy-readback";var after=read.execute(tx->repository.feedback(in.schema(),in.name()));boolean matches=AiCreation.feedbackMatches(p.feedback.subList(0,index+1),after,json);
                var stored=new LinkedHashMap<String,Object>(p.created);stored.put("feedback",after);stored.put("copySource",in.source());
                stage="copy-history";write.executeWithoutResult(tx->history.after(in.schema(),"PROFILE",in.name(),key,matches?"VERIFIED":"UNCERTAIN",json.writeValueAsString(stored)));
                if(!matches){p.blocked=true;return result(p,false,"copy-readback","creation.readbackMismatch");}p.copied++;return result(p,true,"copied","");
            }catch(RuntimeException ex){p.blocked=true;String followup=called?observeFailure(p,historyKey,stage,ex):"";return result(p,false,stage,(called?"creation.partial":"creation.copyStopped")+"\n"+detail(ex)+followup);}
        });}
    }
    private void requireCreated(Plan p){if(!AiCreation.fingerprint(repository.snapshot(p.input.schema(),p.input.kind(),p.input.name()),json).equals(AiCreation.fingerprint(p.created,json)))throw AiCreation.error(409,"targetChanged");}
    private String observeFailure(Plan p,String key,String stage,RuntimeException failure){
        // Never retry the mutating API. Record only what can actually be observed after its error.
        if(key==null)return "";
        try{var data=new LinkedHashMap<String,Object>(read.execute(tx->repository.snapshot(p.input.schema(),p.input.kind(),p.input.name())));
            data.put("failedStage",stage);data.put("error",detail(failure));
            if(stage.startsWith("copy")||stage.equals("FEEDBACK"))data.put("feedback",read.execute(tx->repository.feedback(p.input.schema(),p.input.name())));
            write.executeWithoutResult(tx->history.after(p.input.schema(),p.input.kind().name(),p.input.name(),key,"UNCERTAIN",json.writeValueAsString(data)));return "";
        }catch(RuntimeException ex){return "\ncreation.followupFailed\n"+detail(ex);}
    }
    private static String objectId(Map<String,Object> data,Kind kind){var fields=(Map<?,?>)data.get(kind==Kind.PROFILE?"profile":"object");return Objects.toString(fields.get(kind==Kind.PROFILE?"PROFILE_ID":"ID"),null);}
    private static Result result(Plan p,boolean verified,String stage,String detail){return new Result(verified,stage,detail,p.input.name(),p.copied,p.feedback.size(),verified&&p.created!=null&&p.copied==p.feedback.size());}
    private static String detail(RuntimeException ex){return ex instanceof com.dbcompanion.common.exception.MetadataEditException e?e.userMessage():CredentialCatalogRepository.error(ex);}
}
