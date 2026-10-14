package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProblemEvaluationService {
    private final SessionDataSource source;
    private final AppRecordRepository records;
    private final AiAssistantRepository ai;
    private final ProfileHistoryRepository packages;
    private final JsonMapper json;
    private final com.dbcompanion.common.config.SelectAiExecutionSettings settings;
    private final TransactionTemplate read,generate,write;
    public ProblemEvaluationService(SessionDataSource source,AppRecordRepository records,AiAssistantRepository ai,ProfileHistoryRepository packages,JsonMapper json,com.dbcompanion.common.config.SelectAiExecutionSettings settings) {
        this.source=source;this.records=records;this.ai=ai;this.packages=packages;this.json=json;this.settings=settings;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(15);
        generate=new TransactionTemplate(manager);generate.setReadOnly(true);generate.setTimeout(settings.generateTransactionTimeoutSeconds());
        write=new TransactionTemplate(manager);write.setTimeout(15);
    }
    private String schema(PoolSession s){String schema=s.metadata().selectedSchema();QueryArchive.owner(schema,s.metadata().info().username());return schema;}
    private <T>T tx(PoolSession s,TransactionTemplate transaction,Supplier<T> work){
        source.bind(s.pool(),s.metadata().info().username(),s.metadata().assistant());
        try{return transaction.execute(status->{records.require(schema(s),s.metadata().info().username());return work.get();});}
        finally{source.clear();}
    }
    public ProblemEvaluation.Prepared preview(PoolSession s,String parentId,String attemptId,String criteria,Locale locale) {
        var state=s.metadata().problemEvaluation();synchronized(state){return tx(s,read,()->{
            var selected=s.metadata().assistant().selected();
            if(selected==null)throw new AiAssistant.Failure(409,"assistant.chooseFirst","AI 도우미 설정에서 프로필을 먼저 선택해 주세요.");
            var context=ProblemEvaluation.context(records.problemDetail(schema(s),ProblemQuestion.id(parentId)),attemptId,criteria,json);
            return state.prepare(context,ai.profile(selected),s.metadata().assistant().maxTokens(),UiMessages.supported(locale).getLanguage(),schema(s),Instant.now());
        });}
    }
    public ProblemEvaluation.Report review(PoolSession s,String token,boolean consent) {
        var state=s.metadata().problemEvaluation();
        var prepared=state.consume(token,consent,schema(s),s.metadata().assistant().selected(),Instant.now());
        Instant started=Instant.now();long start=System.nanoTime();ProblemEvaluation.Report result=null;boolean[] called={false};
        try{
            String text=tx(s,generate,()->{
                var context=prepared.context();var reviewer=prepared.preview().profile();
                var current=ProblemEvaluation.context(records.problemDetail(schema(s),context.parentId()),context.attemptId(),context.additionalCriteria(),json);
                if(!context.equals(current)||!Objects.equals(prepared.sessionMaxTokens(),s.metadata().assistant().maxTokens())||!reviewer.equals(ai.profile(reviewer.selection()))
                    ||!Objects.equals(reviewer.selection(),s.metadata().assistant().selected()))throw AiAssistant.stale();
                String owner=packages.packageOwner(reviewer.selection().owner());called[0]=true;
                return ai.explain(owner,reviewer.selection().name(),prepared.preview().source(),settings.generateTimeoutSeconds());
            });
            String safe=ProblemQuestion.text(text,64_000,"evaluation.invalid");
            result=new ProblemEvaluation.Report(UUID.randomUUID().toString(),prepared.context(),prepared.preview().profile(),prepared.sessionMaxTokens(),started,(System.nanoTime()-start)/1_000_000,safe);
            return result;
        }catch(RuntimeException ex){
            if(!called[0]&&ex instanceof AiAssistant.Failure)throw ex;
            throw new AiAssistant.Failure(503,"evaluation.callFailed","AI 검토를 완료하지 못했습니다. 사용량이 발생했을 수 있으며 자동 재시도하지 않았습니다.");
        }finally{state.finish(result);}
    }
    public ProblemEvaluation.Saved save(PoolSession s,String reportId,ProblemEvaluation.Verdict verdict,String reason,boolean confirmed) {
        if(!confirmed)throw new IllegalArgumentException("Explicit decision confirmation required");
        var state=s.metadata().problemEvaluation();synchronized(state){
            var report=state.report(ProblemQuestion.id(reportId));
            var value=ProblemEvaluation.decision(report,verdict,reason,s.metadata().info().username(),Instant.now());
            return tx(s,write,()->{
                String owner=schema(s);records.lockProblem(owner,value.parentId());
                var existing=records.detail(owner,value.id(),ProblemEvaluation.TYPE);
                if(existing!=null){
                    var saved=json.treeToValue(existing.payload(),ProblemEvaluation.Saved.class);
                    if(saved.verdict()!=value.verdict()||!saved.reason().equals(value.reason()))throw AiAssistant.stale();
                    return saved;
                }
                var detail=records.problemDetail(owner,value.parentId());
                if(detail.attempts().stream().noneMatch(a->a.id().equals(value.attemptId())))throw AiAssistant.stale();
                if(records.evaluations(owner,value.parentId()).size()>=100)throw new AiAssistant.Failure(409,"evaluation.limit","질문당 평가 기록은 최대 100개입니다.");
                var payload=json.valueToTree(value);
                if(json.writeValueAsString(payload).length()>ProblemQuestion.MAX_PAYLOAD)throw new AiAssistant.Failure(413,"problemQuestion.limit","선택한 저장 자료가 보관 한도를 초과합니다.");
                records.begin(owner,value.id(),ProblemEvaluation.TYPE,payload);
                if(!records.finish(owner,value.id(),"SUCCEEDED",payload))throw AiAssistant.stale();return value;
            });
        }
    }
    public List<ProblemEvaluation.Saved> history(PoolSession s,String parentId){return tx(s,read,()->{
        String id=ProblemQuestion.id(parentId);records.problemDetail(schema(s),id);return records.evaluations(schema(s),id);
    });}
}
