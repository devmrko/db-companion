package com.dbcompanion.service;

import com.dbcompanion.model.QuestionLanguage;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.config.SelectAiExecutionSettings;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.SelectAiTest;
import com.dbcompanion.model.SelectAiProgress;
import com.dbcompanion.model.SelectAiProgress.Stage;
import com.dbcompanion.model.SelectAiComparison;
import com.dbcompanion.model.SelectAiReview;
import com.dbcompanion.model.SelectAiResultReview;
import com.dbcompanion.model.SelectAiEvidence;
import com.dbcompanion.model.SelectAiInspection;
import com.dbcompanion.model.OntologyRelations;
import com.dbcompanion.model.SelectAiTest.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SelectAiTestService {
    private final SessionDataSource source;
    private final AiAssistantRepository ai;
    private final DatabaseRepository database;
    private final ProfileHistoryRepository packages;
    private final TransactionTemplate read,generate,execute;
    private final OntologyQueryRepository rows;
    private final OntologyRepository ontology;
    private final SelectAiInspectionService inspections;
    private final JsonMapper json;
    private final BusinessGlossaryService glossary;
    private final QuestionAnalysisService questionAnalysis;
    private final SelectAiExecutionSettings executionSettings;
    public SelectAiTestService(SessionDataSource source,AiAssistantRepository ai,DatabaseRepository database,ProfileHistoryRepository packages,OntologyQueryRepository rows,OntologyRepository ontology,SelectAiInspectionService inspections,JsonMapper json){
        this(source,ai,database,packages,rows,ontology,inspections,json,null);
    }
    public SelectAiTestService(SessionDataSource source,AiAssistantRepository ai,DatabaseRepository database,ProfileHistoryRepository packages,OntologyQueryRepository rows,OntologyRepository ontology,SelectAiInspectionService inspections,JsonMapper json,BusinessGlossaryService glossary){
        this(source,ai,database,packages,rows,ontology,inspections,json,glossary,new SelectAiExecutionSettings(SelectAiExecutionSettings.DEFAULT_SECONDS));
    }
    public SelectAiTestService(SessionDataSource source,AiAssistantRepository ai,DatabaseRepository database,ProfileHistoryRepository packages,OntologyQueryRepository rows,OntologyRepository ontology,SelectAiInspectionService inspections,JsonMapper json,BusinessGlossaryService glossary,SelectAiExecutionSettings executionSettings){
        this(source,ai,database,packages,rows,ontology,inspections,json,glossary,executionSettings,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public SelectAiTestService(SessionDataSource source,AiAssistantRepository ai,DatabaseRepository database,ProfileHistoryRepository packages,OntologyQueryRepository rows,OntologyRepository ontology,SelectAiInspectionService inspections,JsonMapper json,BusinessGlossaryService glossary,SelectAiExecutionSettings executionSettings,QuestionAnalysisService questionAnalysis){
        this.source=source;this.ai=ai;this.database=database;this.packages=packages;
        this.questionAnalysis=questionAnalysis;
        this.executionSettings=Objects.requireNonNull(executionSettings);
        this.rows=rows;this.ontology=ontology;this.inspections=inspections;this.json=json;this.glossary=glossary;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(10);
        generate=new TransactionTemplate(manager);generate.setReadOnly(true);generate.setTimeout(executionSettings.generateTransactionTimeoutSeconds());
        var strict=new DataSourceTransactionManager(source);strict.setEnforceReadOnly(true);
        // Includes profile/evidence freshness checks and transaction cleanup.
        execute=new TransactionTemplate(strict);execute.setReadOnly(true);execute.setTimeout(executionSettings.transactionTimeoutSeconds());
    }
    public int executionTimeoutSeconds(){return executionSettings.sqlTimeoutSeconds();}
    private <T>T query(PoolSession session,boolean generating,Supplier<T> work){
        source.bind(session.pool(),session.metadata().info().username());
        try{return (generating?generate:read).execute(status->generating
                ?JdbcNetworkTimeout.execute(source,executionSettings.generateNetworkTimeoutMillis(),work):work.get());}finally{source.clear();}
    }
    public Options options(PoolSession session,boolean refresh){
        var state=session.metadata().aiTest();synchronized(state){
            var choices=state.profiles(refresh,()->query(session,false,ai::profiles));
            return new Options(session.metadata().info().username(),state.selected(),choices,state.running(),state.latest(),state.executionResult(),state.prompt(),state.review().result(),session.metadata().schemas(),session.metadata().selectedSchema(),state.evidence().selected(),state.inspection(),state.resultReview().result());
        }
    }
    public void select(PoolSession session,String name){
        var state=session.metadata().aiTest();synchronized(state){
            state.idle();
            if(name==null||name.isBlank()){state.select(null);return;}
            if(name.length()>128||name.indexOf('\0')>=0)throw AiAssistant.stale();
            var selection=new AiAssistant.Selection(session.metadata().info().username(),name);
            query(session,false,()->ai.profile(selection));state.select(selection);
        }
    }
    public Detail detail(PoolSession session){
        var state=session.metadata().aiTest();synchronized(state){
            var selected=selected(state);
            return state.detail(selected.name(),()->query(session,false,()->{
                var rows=database.profiles(selected.owner(),true,selected.name());
                if(rows.size()!=1)throw AiAssistant.stale();
                return new Detail(rows.getFirst(),database.profileAttributes(selected.owner(),true,selected.name()));
            }));
        }
    }
    private AiAssistant.Selection selected(State state){
        var selected=state.selected();if(selected==null)throw new AiAssistant.Failure(409,"aitest.choose","프로필을 선택해 주세요.");return selected;
    }
    private OntologyInquiry.Dataset evidenceData(PoolSession session,String schema,boolean refresh){
        SelectAiEvidence.scope(schema,session.metadata().schemas());
        return session.metadata().aiTest().evidence().dataset(schema,refresh,()->query(session,false,()->{
            var entries=ontology.relationshipEntries(schema,session.metadata().info().username());var now=Instant.now().toString();
            return new OntologyInquiry.Dataset(schema,entries,OntologyRelations.analyze(session.metadata().info().database(),schema,entries,now),now);
        }));
    }
    public OntologyInquiry.Options evidenceOptions(PoolSession session,String schema,boolean refresh){
        var state=session.metadata().aiTest();synchronized(state){state.invalidateRequests();
            var data=evidenceData(session,schema,refresh);
            return new OntologyInquiry.Options(schema,data.entries().stream().map(e->new OntologyInquiry.Choice(e.document().source().table(),e.document().meaning().concept(),e.state(),e.revision())).toList(),data.checkedAt());
        }
    }
    public OntologyInquiry.Search evidenceSearch(PoolSession session,String schema,String question,String anchor){
        return evidenceSearch(session,schema,question,anchor,QuestionLanguage.KO);
    }
    public OntologyInquiry.Search evidenceSearch(PoolSession session,String schema,String question,String anchor,QuestionLanguage language){
        var state=session.metadata().aiTest();synchronized(state){state.invalidateRequests();evidenceData(session,schema,false);
            var analysis=Objects.requireNonNull(questionAnalysis).analyze(session,question,language);
            return state.evidence().search(schema,question,anchor,analysis);}
    }
    public SelectAiEvidence.Snapshot evidenceChoose(PoolSession session,String id,String route){
        var state=session.metadata().aiTest();synchronized(state){state.invalidateRequests();var result=state.evidence().choose(id,route,json);
            SelectAiEvidence.scope(result.schema(),session.metadata().schemas());return result;
        }
    }
    public SelectAiEvidence.Snapshot evidenceDefinitions(PoolSession session,String schema,String question,List<String> tables){
        var state=session.metadata().aiTest();synchronized(state){state.idle();state.invalidateRequests();
            evidenceData(session,schema,false);
            return state.evidence().definitions(schema,question,tables,json);
        }
    }
    private void verifyEvidence(PoolSession session,SelectAiEvidence.Snapshot evidence){
        if(evidence==null)return;SelectAiEvidence.scope(evidence.schema(),session.metadata().schemas());
        ontology.require(evidence.schema(),session.metadata().info().username());
        var current=evidence.entries().stream().map(e->ontology.entry(evidence.schema(),e.document().source().table(),0)).toList();
        if(current.stream().anyMatch(Objects::isNull))throw SelectAiEvidence.stale();SelectAiEvidence.verify(evidence,current);
    }
    public Prepared preview(PoolSession session,Action action,String question,boolean useOntology,String evidenceHash,Locale locale){
        return preview(session,action,question,useOntology,evidenceHash,locale,null);
    }
    public Prepared preview(PoolSession session,Action action,String question,boolean useOntology,String evidenceHash,Locale locale,com.dbcompanion.model.BusinessGlossary.Selection terms){
        SelectAiTest.question(question);if(action==null)throw new IllegalArgumentException("Missing action");
        var state=session.metadata().aiTest();synchronized(state){
            state.idle();var selected=selected(state);
            var evidence=state.evidence().resolve(useOntology,evidenceHash,question);
            var profile=query(session,false,()->{verifyEvidence(session,evidence);return ai.profile(selected);});
            var dictionary=terms==null||!terms.enabled()?null:Objects.requireNonNull(glossary).resolve(session,question,selected.name(),terms);
            return state.prepare(selected.owner(),profile,action,question,UiMessages.supported(locale).getLanguage(),Instant.now(),evidence,null,dictionary);
        }
    }
    /** Builds a visible, one-use request from user-authored conditions; this never calls a provider. */
    public Prepared conditionPreview(PoolSession session,Action action,String question,SelectAiTest.ConditionConfirmation confirmation,boolean useOntology,String evidenceHash,Locale locale){
        return conditionPreview(session,action,question,confirmation,useOntology,evidenceHash,locale,null);
    }
    public Prepared conditionPreview(PoolSession session,Action action,String question,SelectAiTest.ConditionConfirmation confirmation,boolean useOntology,String evidenceHash,Locale locale,com.dbcompanion.model.BusinessGlossary.Selection terms){
        SelectAiTest.question(question);if(action==null||confirmation==null)throw new AiAssistant.Failure(400,"aitest.conditionsInvalid","확인 질문·답변과 조건을 확인해 주세요.");
        if(json.writeValueAsString(Map.of("confirmationQuestion",confirmation.question(),"confirmationAnswer",confirmation.answer(),"conditions",confirmation.conditions())).length()>com.dbcompanion.model.ProblemQuestion.MAX_DESCRIPTION)throw new AiAssistant.Failure(413,"aitest.conditionsTooLong","확정 조건 보관 한도를 초과합니다. 내용을 줄여 주세요.");
        var state=session.metadata().aiTest();synchronized(state){
            state.idle();var selected=selected(state);var evidence=state.evidence().resolve(useOntology,evidenceHash,question);
            var profile=query(session,false,()->{verifyEvidence(session,evidence);return ai.profile(selected);});
            var dictionary=terms==null||!terms.enabled()?null:Objects.requireNonNull(glossary).resolve(session,question,selected.name(),terms);
            return state.prepare(selected.owner(),profile,action,question,UiMessages.supported(locale).getLanguage(),Instant.now(),evidence,confirmation,dictionary);
        }
    }
    public void cancel(PoolSession session,String token){session.metadata().aiTest().cancel(token);}
    public SelectAiComparison.Plan comparisonPreview(PoolSession session,String left,String right,String question,Locale locale){
        SelectAiTest.question(question);if(left==null||right==null||left.length()>128||right.length()>128)throw AiAssistant.stale();
        return query(session,false,()->{String owner=session.metadata().info().username();var a=ai.profile(new AiAssistant.Selection(owner,left));var b=ai.profile(new AiAssistant.Selection(owner,right));return session.metadata().aiComparison().prepare(a,b,question,UiMessages.supported(locale).getLanguage(),Instant.now());});
    }
    public SelectAiTest.Outcome comparisonGenerate(PoolSession session,String token,boolean consent){
        var state=session.metadata().aiComparison();var preview=state.consume(token,consent,Instant.now());var started=Instant.now();long nanos=System.nanoTime();boolean[] called={false};SelectAiTest.Outcome result;
        try{String text=query(session,true,()->{if(!preview.profile().equals(ai.profile(preview.profile().selection())))throw AiAssistant.stale();called[0]=true;return ai.generate(packages.packageOwner(preview.profile().selection().owner()),preview.profile().selection().name(),preview.source(),SelectAiTest.Action.SQL,executionSettings.generateTimeoutSeconds());});boolean rejected=!SelectAiReview.sqlResponse(text);result=new SelectAiTest.Outcome(UUID.randomUUID().toString(),SelectAiTest.Action.SQL,preview.profile(),state.plan().question(),started,elapsed(nanos),text,rejected?UiMessages.text("aitest.notSql","SQL 생성 응답이 실행 가능한 SQL이 아닙니다. 원문을 확인해 주세요."):null,rejected?SelectAiReview.responseCode(text):null,rejected?"sql-response":"complete");}
        catch(RuntimeException ex){result=new SelectAiTest.Outcome(UUID.randomUUID().toString(),SelectAiTest.Action.SQL,preview.profile(),state.plan().question(),started,elapsed(nanos),null,!called[0]&&ex instanceof AiAssistant.Failure?ex.getMessage():UiMessages.text(called[0]?"aitest.callError":"aitest.preflightError",called[0]?"응답을 확인하지 못했습니다. 자동 재시도하지 않았습니다.":"호출 전 프로필 확인 단계에서 중단했습니다."),CredentialCatalogRepository.error(ex),called[0]?"generate":"preflight");}
        state.finish(token,result);return result;
    }
    public SelectAiComparison.ShowPromptPlan comparisonShowPromptPreview(PoolSession session,Locale locale){return session.metadata().aiComparison().prepareShowPrompt(UiMessages.supported(locale).getLanguage(),Instant.now());}
    public SelectAiTest.Outcome comparisonShowPrompt(PoolSession session,String token,boolean consent){
        var state=session.metadata().aiComparison();var preview=state.consumePrompt(token,consent,Instant.now());var started=Instant.now();long nanos=System.nanoTime();boolean[] called={false};SelectAiTest.Outcome result;
        try{String text=query(session,true,()->{if(!preview.profile().equals(ai.profile(preview.profile().selection())))throw AiAssistant.stale();called[0]=true;return ai.generate(packages.packageOwner(preview.profile().selection().owner()),preview.profile().selection().name(),preview.source(),SelectAiTest.Action.PROMPT,executionSettings.generateTimeoutSeconds());});result=new SelectAiTest.Outcome(UUID.randomUUID().toString(),SelectAiTest.Action.PROMPT,preview.profile(),state.plan().question(),started,elapsed(nanos),text,null,null,"showprompt");}
        catch(RuntimeException ex){result=new SelectAiTest.Outcome(UUID.randomUUID().toString(),SelectAiTest.Action.PROMPT,preview.profile(),state.plan().question(),started,elapsed(nanos),null,!called[0]&&ex instanceof AiAssistant.Failure?ex.getMessage():UiMessages.text(called[0]?"aitest.callError":"aitest.preflightError",called[0]?"응답을 확인하지 못했습니다. 자동 재시도하지 않았습니다.":"호출 전 프로필 확인 단계에서 중단했습니다."),CredentialCatalogRepository.error(ex),called[0]?"showprompt":"preflight");}
        state.finishPrompt(token,result);return result;
    }
    public SelectAiComparison.Result comparisonResult(PoolSession session){return session.metadata().aiComparison().result();}
    public SelectAiInspection.Snapshot comparisonInspection(PoolSession session,String side){var state=session.metadata().aiComparison();var request=state.beginInspection(side,null);try{var snapshot=inspections.loadDetached(session,request.profile(),request.question());state.finishInspection(request.generation(),side,snapshot);return snapshot;}catch(RuntimeException ex){state.failInspection(request.generation(),side);throw ex;}}
    public SelectAiInspection.Snapshot comparisonTable(PoolSession session,String side,String id,String owner,String name){var state=session.metadata().aiComparison();var request=state.beginInspection(side,id);try{var snapshot=inspections.tableDetached(session,request.snapshot(),owner,name);state.finishInspection(request.generation(),side,snapshot);return snapshot;}catch(RuntimeException ex){state.failInspection(request.generation(),side);throw ex;}}
    public SelectAiInspection.Snapshot comparisonFeedbackDetail(PoolSession session,String side,String id,String rowId){var state=session.metadata().aiComparison();var request=state.beginInspection(side,id);try{var snapshot=inspections.feedbackDetailDetached(session,request.snapshot(),rowId);state.finishInspection(request.generation(),side,snapshot);return snapshot;}catch(RuntimeException ex){state.failInspection(request.generation(),side);throw ex;}}
    public SelectAiComparison.AiPreview comparisonAiPreview(PoolSession session,Set<String> fields){var selected=session.metadata().assistant().selected();if(selected==null)throw new AiAssistant.Failure(409,"assistant.chooseFirst","AI 도우미 설정에서 프로필을 먼저 선택해 주세요.");return query(session,false,()->session.metadata().aiComparison().prepareAi(ai.profile(selected),fields,Instant.now()));}
    public String comparisonAi(PoolSession session,String token,boolean consent){var state=session.metadata().aiComparison();var preview=state.consumeAi(token,consent,Instant.now());try{String text=query(session,true,()->{if(!preview.profile().equals(ai.profile(preview.profile().selection())))throw AiAssistant.stale();return ai.explain(packages.packageOwner(preview.profile().selection().owner()),preview.profile().selection().name(),preview.source(),executionSettings.generateTimeoutSeconds());});state.finishAi(preview.generation(),preview.token(),text);return text;}catch(RuntimeException ex){String error=ex instanceof AiAssistant.Failure?ex.getMessage():UiMessages.text("aitest.callError","응답을 확인하지 못했습니다. 자동 재시도하지 않았습니다.");state.finishAi(preview.generation(),preview.token(),error);return error;}}
    public SelectAiComparison.SavePreview comparisonSavePreview(PoolSession session,String generation,String side,String resultId){return session.metadata().aiComparison().prepareSave(generation,side,resultId,Instant.now());}
    public SelectAiComparison.SaveBegin comparisonSave(PoolSession session,String generation,String side,String resultId,String token,String purpose){return session.metadata().aiComparison().beginSave(generation,side,resultId,token,purpose,Instant.now());}
    public void comparisonSaveFinished(PoolSession session,String token,String id){session.metadata().aiComparison().finishSave(token,id);}
    public void comparisonSaveAborted(PoolSession session,String token){session.metadata().aiComparison().abortSave(token);}
    public void comparisonSaveUnconfirmed(PoolSession session,String token){session.metadata().aiComparison().unconfirmedSave(token);}
    public SelectAiTest.ProblemSave problemSavePreview(PoolSession session,String resultId){return session.metadata().aiTest().prepareProblemSave(resultId,Instant.now());}
    public SelectAiTest.ProblemSaveBegin problemSave(PoolSession session,String resultId,String token,String purpose){return session.metadata().aiTest().beginProblemSave(resultId,token,purpose,Instant.now());}
    public void problemSaveFinished(PoolSession session,String token,String id){session.metadata().aiTest().finishProblemSave(token,id);}
    public void problemSaveAborted(PoolSession session,String token){session.metadata().aiTest().abortProblemSave(token);}
    public void problemSaveUnconfirmed(PoolSession session,String token){session.metadata().aiTest().unconfirmedProblemSave(token);}
    public SelectAiReview.Prepared reviewPreview(PoolSession session,String promptId,Locale locale){
        var state=session.metadata().aiTest();synchronized(state){
            var snapshot=state.reviewable(promptId);var selection=session.metadata().assistant().selected();
            if(selection==null)throw new AiAssistant.Failure(409,"assistant.chooseFirst","AI 도우미 설정에서 프로필을 먼저 선택해 주세요.");
            var reviewer=query(session,false,()->{
                verifyEvidence(session,snapshot.evidence());
                if(!snapshot.profile().equals(ai.profile(snapshot.profile().selection())))throw AiAssistant.stale();
                return ai.profile(selection);
            });
            state.prepareReview();
            return state.review().prepare(snapshot,state.latest(),reviewer,UiMessages.supported(locale).getLanguage(),Instant.now(),state.inspection());
        }
    }
    public SelectAiReview.Result review(PoolSession session,String token,boolean consent){
        var state=session.metadata().aiTest();final SelectAiReview.Prepared prepared;
        synchronized(state){
            state.idle();prepared=state.review().consume(token,consent,session.metadata().info().username(),session.metadata().assistant().selected(),Instant.now());
            state.beginReview();
        }
        var before=prepared.preview().profile();var started=Instant.now();long nanos=System.nanoTime();
        boolean[] called={false};SelectAiReview.Result result=null;
        try{
            String text=query(session,true,()->{
                verifyEvidence(session,prepared.prompt().evidence());
                if(!Objects.equals(session.metadata().assistant().selected(),before.selection())
                        ||!before.equals(ai.profile(before.selection()))
                        ||!prepared.prompt().profile().equals(ai.profile(prepared.prompt().profile().selection())))throw AiAssistant.stale();
                String owner=packages.packageOwner(before.selection().owner());called[0]=true;
                return ai.explain(owner,before.selection().name(),prepared.preview().source(),executionSettings.generateTimeoutSeconds());
            });
            result=new SelectAiReview.Result(prepared.prompt().id(),before,started,elapsed(nanos),text,null,null);
        }catch(RuntimeException ex){
            String stage=called[0] ? "AI review request" : "review profile preflight";
            String message=!called[0]&&ex instanceof AiAssistant.Failure?ex.getMessage():UiMessages.text(called[0]?"aitest.callError":"aitest.preflightError",called[0]?
                    "응답을 확인하지 못했습니다. 사용량이 발생했을 수 있으며 자동 재시도하지 않았습니다.":"호출 전 프로필 확인 단계에서 중단했습니다. 프로필을 새로고침해 주세요.");
            result=new SelectAiReview.Result(prepared.prompt().id(),before,started,elapsed(nanos),null,error(message,stage,ex),code(ex));
        }finally{synchronized(state){state.review().finish(result);state.finishReview();}}
        return result;
    }
    public AiAssistant.Preview resultReviewPreview(PoolSession session,String resultId,String baseline,Locale locale){
        var state=session.metadata().aiTest();synchronized(state){
            var outcome=state.executable(resultId);SelectAiResultReview.verify(outcome,state.executionResult());
            var selection=session.metadata().assistant().selected();
            if(selection==null)throw new AiAssistant.Failure(409,"assistant.chooseFirst","AI 도우미 설정에서 프로필을 먼저 선택해 주세요.");
            var reviewer=query(session,false,()->ai.profile(selection));
            state.invalidateRequests();
            // Review the captured definitions, not a reconstructed current glossary/catalog.
            return state.resultReview().prepare(session.metadata().info().username(),outcome,state.executionResult(),reviewer,
                    baseline,UiMessages.supported(locale).getLanguage(),Instant.now());
        }
    }
    public SelectAiResultReview.Result resultReview(PoolSession session,String token,boolean consent){
        var state=session.metadata().aiTest();final SelectAiResultReview.Prepared prepared;
        synchronized(state){
            state.idle();if(state.latest()==null)throw AiAssistant.stale();state.executable(state.latest().id());
            prepared=state.resultReview().consume(token,consent,session.metadata().info().username(),
                    session.metadata().assistant().selected(),state.latest(),state.executionResult(),Instant.now());
            state.beginReview();
        }
        var reviewer=prepared.preview().profile();var started=Instant.now();long nanos=System.nanoTime();
        boolean[] called={false};SelectAiResultReview.Result result=null;
        try{
            String text=query(session,true,()->{
                if(!Objects.equals(session.metadata().assistant().selected(),reviewer.selection())
                        ||!reviewer.equals(ai.profile(reviewer.selection())))throw AiAssistant.stale();
                String owner=packages.packageOwner(reviewer.selection().owner());called[0]=true;
                return ai.explain(owner,reviewer.selection().name(),prepared.preview().source(),executionSettings.generateTimeoutSeconds());
            });
            if(text==null||text.isBlank())throw new AiAssistant.Failure(502,"aitest.resultReview.empty","AI 검토 응답이 비어 있습니다. 자동 재시도하지 않았습니다.");
            result=new SelectAiResultReview.Result(prepared.preview().reference(),prepared.sqlHash(),prepared.executedAt(),reviewer,started,elapsed(nanos),text,null,null);
        }catch(RuntimeException ex){
            String message=!called[0]&&ex instanceof AiAssistant.Failure?ex.getMessage():UiMessages.text(called[0]?"aitest.callError":"aitest.preflightError",called[0]?
                    "응답을 확인하지 못했습니다. 사용량이 발생했을 수 있으며 자동 재시도하지 않았습니다.":"호출 전 프로필 확인 단계에서 중단했습니다. 프로필을 새로고침해 주세요.");
            result=new SelectAiResultReview.Result(prepared.preview().reference(),prepared.sqlHash(),prepared.executedAt(),reviewer,started,elapsed(nanos),null,
                    error(message,called[0]?"AI result review request":"result review profile preflight",ex),code(ex));
        }finally{synchronized(state){state.resultReview().finish(result);state.finishReview();}}
        return result;
    }
    public ExecutionPreview executionPreview(PoolSession session,String id){
        var state=session.metadata().aiTest();synchronized(state){
            var outcome=state.executable(id);var checked=SelectAiReadSql.check(outcome.text());
            query(session,false,()->{verifyEvidence(session,outcome.evidence());if(!outcome.profile().equals(ai.profile(outcome.profile().selection())))throw AiAssistant.stale();return true;});
            return state.prepareExecution(id,checked,Instant.now());
        }
    }
    public ExecutionResult execute(PoolSession session,String token,boolean confirmed){
        return execute(session,token,confirmed,null);
    }
    public ExecutionResult execute(PoolSession session,String token,boolean confirmed,String operationId){
        operationId=SelectAiProgress.requestId(operationId);
        var state=session.metadata().aiTest();var value=state.consumeExecution(token,confirmed,Instant.now());
        var trace=state.progress().begin(operationId,"EXECUTE");trace.step(Stage.CONNECTION);
        long nanos=System.nanoTime();ExecutionResult result=null;
        try{
            source.bind(session.pool(),session.metadata().info().username());
            var data=execute.execute(status->JdbcNetworkTimeout.execute(source,executionSettings.networkTimeoutMillis(),()->{
                trace.step(Stage.PROFILE);
                verifyEvidence(session,value.evidence());
                if(!value.profile().equals(ai.profile(value.profile().selection())))throw AiAssistant.stale();
                var checked=SelectAiReadSql.check(value.preview().sql());
                if(!OntologyQueryService.hash(checked.sql()).equals(value.preview().hash()))throw AiAssistant.stale();
                trace.step(Stage.QUERY);
                var resultRows=rows.execute(value.preview().resultId(),checked.sql(),value.preview().hash(),session.metadata().info().username(),executionSettings.sqlTimeoutSeconds(),()->trace.step(Stage.FETCH));
                trace.step(Stage.CLEANUP);
                return resultRows;
            }));
            result=new ExecutionResult(value.preview().resultId(),data,null,null,elapsed(nanos));
        }catch(RuntimeException ex){
            String message=ex instanceof AiAssistant.Failure?ex.getMessage():UiMessages.text("aitest.executeError","조회 결과를 확인하지 못했습니다. 자동 재시도하지 않았습니다.");
            result=new ExecutionResult(value.preview().resultId(),null,error(message,"read-only SQL execution",ex),code(ex),elapsed(nanos));
        }finally{source.clear();state.finishExecution(result);trace.finish(result!=null&&result.error()==null);}
        return result;
    }
    public Outcome run(PoolSession session,String token,boolean consent){
        return run(session,token,consent,null);
    }
    public Outcome run(PoolSession session,String token,boolean consent,String operationId){
        operationId=SelectAiProgress.requestId(operationId);
        var state=session.metadata().aiTest();
        var prepared=state.consume(token,consent,session.metadata().info().username(),Instant.now());
        var trace=state.progress().begin(operationId,prepared.action().name());trace.step(Stage.DEFINITIONS);
        var before=prepared.preview().profile();var started=Instant.now();long nanos=System.nanoTime();
        String id=UUID.randomUUID().toString();boolean[] called={false};Outcome outcome=null;
        try{
            if(prepared.glossary()!=null)Objects.requireNonNull(glossary).verify(session,prepared.glossary());
            trace.step(Stage.CONNECTION);
            String text=query(session,true,()->{
                trace.step(Stage.PROFILE);
                verifyEvidence(session,prepared.evidence());
                if(!before.equals(ai.profile(before.selection())))throw AiAssistant.stale();
                String owner=packages.packageOwner(before.selection().owner());
                called[0]=true;
                trace.step(Stage.AI);
                return ai.generate(owner,before.selection().name(),prepared.preview().source(),prepared.action(),executionSettings.generateTimeoutSeconds());
            });
            trace.step(Stage.RESPONSE);
            boolean rejected=prepared.action()==Action.SQL&&!SelectAiReview.sqlResponse(text);
            outcome=new Outcome(id,prepared.action(),before,prepared.question(),started,elapsed(nanos),text,
                    rejected?UiMessages.text("aitest.notSql","SQL 생성 응답이 실행 가능한 SQL이 아닙니다. 원문을 확인해 주세요."):null,
                    rejected?SelectAiReview.responseCode(text):null,rejected?"sql-response":"complete",prepared.evidence(),prepared.confirmation(),prepared.glossary());
        }catch(RuntimeException ex){
            // Do not expose Oracle/provider messages which can contain questions or secret response data.
            String message=!called[0]&&ex instanceof AiAssistant.Failure?ex.getMessage():UiMessages.text(called[0]?"aitest.callError":"aitest.preflightError",called[0]?
                    "응답을 확인하지 못했습니다. 사용량이 발생했을 수 있으며 자동 재시도하지 않았습니다.":
                    "호출 전 프로필 확인 단계에서 중단했습니다. 프로필을 새로고침해 주세요.");
            String stage=called[0] ? "AI request" : "profile preflight";
            outcome=new Outcome(id,prepared.action(),before,prepared.question(),started,elapsed(nanos),null,error(message,stage,ex),
                    code(ex),called[0]?"generate":"preflight",prepared.evidence(),prepared.confirmation(),prepared.glossary());
        }finally{state.finish(outcome);trace.finish(outcome!=null&&outcome.error()==null);}
        return outcome;
    }
    /** Keep the persistence-facing code contract short; diagnostics stay in the user-visible error body. */
    private static String code(Throwable error){return CredentialCatalogRepository.error(error);}
    private static String error(String message,String stage,Throwable failure){
        String detail=AiErrorExplanation.explain(stage,failure).display();
        return detail.isBlank()?message:message+"\n"+detail;
    }
    private long elapsed(long nanos){return (System.nanoTime()-nanos)/1_000_000;}
}
