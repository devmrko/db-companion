package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Batch orchestration only; SQL execution is deliberately absent. */
@Service
public class SelectAiBatchService {
    private final AppRecordRepository records; private final AiAssistantRepository ai; private final ProfileHistoryRepository packages; private final SessionDataSource source; private final ProblemQuestionService problems; private final TransactionTemplate read,generate;
    public SelectAiBatchService(AppRecordRepository records,AiAssistantRepository ai,ProfileHistoryRepository packages,SessionDataSource source,ProblemQuestionService problems){this.records=records;this.ai=ai;this.packages=packages;this.source=source;this.problems=problems;var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(15);generate=new TransactionTemplate(manager);generate.setReadOnly(true);generate.setTimeout(100);}
    private <T>T tx(PoolSession s,TransactionTemplate template,Supplier<T> work){source.bind(s.pool(),s.metadata().info().username());try{return template.execute(x->work.get());}finally{source.clear();}}
    private String schema(PoolSession s){String schema=s.metadata().selectedSchema(),owner=s.metadata().info().username();QueryArchive.owner(schema,owner);return schema;}
    public SelectAiBatch.Plan prepare(PoolSession s,List<String> ids,String profile){if(ids==null||ids.isEmpty()||ids.size()>20)throw new AiAssistant.Failure(400,"batch.items","1~20개의 등록 질문을 선택해 주세요.");return tx(s,read,()->{String owner=s.metadata().info().username();records.require(schema(s),owner);var p=ai.profile(new AiAssistant.Selection(owner,profile));var parents=new ArrayList<ProblemQuestion.Parent>();for(String id:ids)parents.add(records.problemDetail(schema(s),ProblemQuestion.id(id)).parent());var state=s.metadata().aiBatch();synchronized(state){var plan=state.prepare(parents.stream().map(x->new SelectAiBatch.Item(x.id(),x.question(),fingerprint(p,x.updatedAt()),null,null,null,null)).toList(),Instant.now());for(int i=0;i<parents.size();i++)state.context(plan.items().get(i).token(),p,parents.get(i).updatedAt(),"en",SelectAiTest.prompt(SelectAiTest.Action.SQL,parents.get(i).question(),"en"));return state.plan();}});}
    private String fingerprint(AiAssistant.Profile p,Instant version){return p.selection().owner()+":"+p.selection().name()+":"+p.provider()+":"+p.model()+":"+p.version()+":"+version;}
    public SelectAiBatch.Start start(PoolSession s,String generation,String token,boolean consent){return s.metadata().aiBatch().consumeNext(generation,token,consent,Instant.now());}
    public SelectAiBatch.Item run(PoolSession s,String generation,String token,boolean consent){
        var state=s.metadata().aiBatch();
        var start=state.consumeNext(generation,token,consent,Instant.now());
        boolean[] providerCalled={false};
        try{
            var item=start.item();var context=state.context(generation,token);
            String owner=s.metadata().info().username();
            if(!owner.equals(context.profile().selection().owner()))throw AiAssistant.stale();
            var parent=tx(s,read,()->records.problemDetail(schema(s),item.parentId()).parent());
            var profile=tx(s,read,()->ai.profile(context.profile().selection()));
            if(!context.profile().equals(profile)||!parent.updatedAt().equals(context.parentVersion())
                    ||!fingerprint(profile,parent.updatedAt()).equals(item.profileFingerprint()))throw AiAssistant.stale();
            String result=tx(s,generate,()->{
                if(!profile.equals(ai.profile(profile.selection())))throw AiAssistant.stale();
                String packageOwner=packages.packageOwner(owner);
                providerCalled[0]=true;
                return ai.generate(packageOwner,profile.selection().name(),context.source(),SelectAiTest.Action.SQL);
            });
            return complete(state,generation,token,result,SelectAiReview.sqlResponse(result)?null:"sql-response",false);
        }catch(RuntimeException ex){
            String code=CredentialCatalogRepository.error(ex);
            String phase=providerCalled[0]?"provider-error":"preflight-stale";
            return complete(state,generation,token,null,code.isBlank()?phase:phase+":"+code,!providerCalled[0]);
        }
    }
    /** Return the completed item while its generation is still locked. */
    private SelectAiBatch.Item complete(SelectAiBatch.State state,String generation,String token,String result,String error,boolean cancelPending){
        synchronized(state){
            state.finish(generation,token,result,error);
            if(cancelPending)state.cancel(generation);
            return state.plan().items().stream().filter(i->i.token().equals(token)).findFirst().orElseThrow(AiAssistant::stale);
        }
    }
    public SelectAiBatch.Plan cancel(PoolSession s,String generation){return s.metadata().aiBatch().cancel(generation);}
    public SelectAiBatch.Plan status(PoolSession s){return tx(s,read,()->{records.require(schema(s),s.metadata().info().username());return s.metadata().aiBatch().plan();});}
    public String save(PoolSession s,String generation,String token,boolean confirmed){
        if(!confirmed)throw new AiAssistant.Failure(400,"batch.saveConsent","생성 결과 저장을 확인해 주세요.");
        schema(s);var state=s.metadata().aiBatch();final SelectAiBatch.Item item;final SelectAiBatch.Context context;
        synchronized(state){String existing=state.beginSave(generation,token);if(existing!=null)return existing;context=state.context(generation,token);item=state.plan().items().stream().filter(i->i.token().equals(token)).findFirst().orElseThrow(AiAssistant::stale);}
        boolean writing=false;
        try{
            if(!context.profile().selection().owner().equals(s.metadata().info().username()))throw AiAssistant.stale();
            var parent=tx(s,read,()->records.problemDetail(schema(s),item.parentId()).parent());
            if(!parent.updatedAt().equals(context.parentVersion()))throw AiAssistant.stale();
            var attempt=new ProblemQuestion.Attempt(1,"",item.parentId(),item.question(),"",item.result(),item.status()==SelectAiBatch.Status.SUCCEEDED?item.result():"",item.error(),context.profile().selection().owner()+"."+context.profile().selection().name(),context.profile().model(),"","","","","BATCH_USER_SELECTED",item.status().name(),item.requestedAt(),item.elapsedMillis(),null,null,null,null);
            writing=true;String id=problems.saveAttempt(s,attempt,context.parentVersion());state.finishSave(generation,token,id);return id;
        }catch(RuntimeException ex){
            boolean uncertain=writing&&!(ex instanceof AiAssistant.Failure)&&!(ex instanceof Ontology.Failure);
            state.failedSave(generation,token,uncertain);
            if(uncertain)throw new AiAssistant.Failure(503,"batch.saveUnconfirmed","SAVE_UNCONFIRMED: 저장 결과를 확인하지 못했습니다. 원래 문제 질문 상세를 확인해 주세요. 자동 재전송하지 않습니다.");
            throw ex;
        }
    }
}
