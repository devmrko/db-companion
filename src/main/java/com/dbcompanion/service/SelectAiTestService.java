package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.SelectAiTest;
import com.dbcompanion.model.SelectAiReview;
import com.dbcompanion.model.SelectAiEvidence;
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
    private final SelectAiExecutionRepository executionRepository;
    private final OntologyQueryRepository rows;
    private final OntologyRepository ontology;
    private final JsonMapper json;
    public SelectAiTestService(SessionDataSource source,AiAssistantRepository ai,DatabaseRepository database,ProfileHistoryRepository packages,SelectAiExecutionRepository executionRepository,OntologyQueryRepository rows,OntologyRepository ontology,JsonMapper json){
        this.source=source;this.ai=ai;this.database=database;this.packages=packages;
        this.executionRepository=executionRepository;this.rows=rows;this.ontology=ontology;this.json=json;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(10);
        generate=new TransactionTemplate(manager);generate.setReadOnly(true);generate.setTimeout(90);
        var strict=new DataSourceTransactionManager(source);strict.setEnforceReadOnly(true);
        execute=new TransactionTemplate(strict);execute.setReadOnly(true);execute.setTimeout(30);
    }
    private <T>T query(PoolSession session,boolean generating,Supplier<T> work){
        source.bind(session.pool(),session.metadata().info().username());
        try{return (generating?generate:read).execute(status->work.get());}finally{source.clear();}
    }
    public Options options(PoolSession session,boolean refresh){
        var state=session.metadata().aiTest();synchronized(state){
            var choices=state.profiles(refresh,()->query(session,false,ai::profiles));
            return new Options(session.metadata().info().username(),state.selected(),choices,state.running(),state.latest(),state.executionResult(),state.prompt(),state.review().result(),session.metadata().schemas(),session.metadata().selectedSchema(),state.evidence().selected(),state.inspection());
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
        var state=session.metadata().aiTest();synchronized(state){state.invalidateRequests();evidenceData(session,schema,false);return state.evidence().search(schema,question,anchor);}
    }
    public SelectAiEvidence.Snapshot evidenceChoose(PoolSession session,String id,String route){
        var state=session.metadata().aiTest();synchronized(state){state.invalidateRequests();var result=state.evidence().choose(id,route,json);
            SelectAiEvidence.scope(result.schema(),session.metadata().schemas());return result;
        }
    }
    private void verifyEvidence(PoolSession session,SelectAiEvidence.Snapshot evidence){
        if(evidence==null)return;SelectAiEvidence.scope(evidence.schema(),session.metadata().schemas());
        ontology.require(evidence.schema(),session.metadata().info().username());
        var current=evidence.entries().stream().map(e->ontology.entry(evidence.schema(),e.document().source().table(),0)).toList();
        if(current.stream().anyMatch(Objects::isNull))throw SelectAiEvidence.stale();SelectAiEvidence.verify(evidence,current);
    }
    public Prepared preview(PoolSession session,Action action,String question,boolean useOntology,String evidenceHash,Locale locale){
        SelectAiTest.question(question);if(action==null)throw new IllegalArgumentException("Missing action");
        var state=session.metadata().aiTest();synchronized(state){
            state.idle();var selected=selected(state);
            var evidence=state.evidence().resolve(useOntology,evidenceHash,question);
            var profile=query(session,false,()->{verifyEvidence(session,evidence);return ai.profile(selected);});
            return state.prepare(selected.owner(),profile,action,question,UiMessages.supported(locale).getLanguage(),Instant.now(),evidence);
        }
    }
    public void cancel(PoolSession session,String token){session.metadata().aiTest().cancel(token);}
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
                return ai.explain(owner,before.selection().name(),prepared.preview().source());
            });
            result=new SelectAiReview.Result(prepared.prompt().id(),before,started,elapsed(nanos),text,null,null);
        }catch(RuntimeException ex){
            result=new SelectAiReview.Result(prepared.prompt().id(),before,started,elapsed(nanos),null,
                    !called[0]&&ex instanceof AiAssistant.Failure?ex.getMessage():UiMessages.text(called[0]?"aitest.callError":"aitest.preflightError",called[0]?
                            "응답을 확인하지 못했습니다. 사용량이 발생했을 수 있으며 자동 재시도하지 않았습니다.":"호출 전 프로필 확인 단계에서 중단했습니다. 프로필을 새로고침해 주세요."),CredentialCatalogRepository.error(ex));
        }finally{synchronized(state){state.review().finish(result);state.finishReview();}}
        return result;
    }
    public ExecutionPreview executionPreview(PoolSession session,String id){
        var state=session.metadata().aiTest();synchronized(state){
            var outcome=state.executable(id);var checked=SelectAiReadSql.check(outcome.text());
            query(session,false,()->{verifyEvidence(session,outcome.evidence());if(!outcome.profile().equals(ai.profile(outcome.profile().selection())))throw AiAssistant.stale();executionRepository.verify(checked,session.metadata().info().username());return true;});
            return state.prepareExecution(id,checked,Instant.now());
        }
    }
    public ExecutionResult execute(PoolSession session,String token,boolean confirmed){
        var state=session.metadata().aiTest();var value=state.consumeExecution(token,confirmed,Instant.now());
        long nanos=System.nanoTime();ExecutionResult result=null;
        try{
            source.bind(session.pool(),session.metadata().info().username());
            var data=execute.execute(status->{
                verifyEvidence(session,value.evidence());
                if(!value.profile().equals(ai.profile(value.profile().selection())))throw AiAssistant.stale();
                var checked=SelectAiReadSql.check(value.preview().sql());
                if(!OntologyQueryService.hash(checked.sql()).equals(value.preview().hash()))throw SelectAiReadSql.blocked();
                executionRepository.verify(checked,session.metadata().info().username());
                return rows.execute(value.preview().resultId(),checked.sql(),value.preview().hash(),session.metadata().info().username());
            });
            result=new ExecutionResult(value.preview().resultId(),data,null,null,elapsed(nanos));
        }catch(RuntimeException ex){
            String message=ex instanceof AiAssistant.Failure?ex.getMessage():UiMessages.text("aitest.executeError","조회 결과를 확인하지 못했습니다. 자동 재시도하지 않았습니다.");
            result=new ExecutionResult(value.preview().resultId(),null,message,CredentialCatalogRepository.error(ex),elapsed(nanos));
        }finally{source.clear();state.finishExecution(result);}
        return result;
    }
    public Outcome run(PoolSession session,String token,boolean consent){
        var state=session.metadata().aiTest();
        var prepared=state.consume(token,consent,session.metadata().info().username(),Instant.now());
        var before=prepared.preview().profile();var started=Instant.now();long nanos=System.nanoTime();
        String id=UUID.randomUUID().toString();boolean[] called={false};Outcome outcome=null;
        try{
            String text=query(session,true,()->{
                verifyEvidence(session,prepared.evidence());
                if(!before.equals(ai.profile(before.selection())))throw AiAssistant.stale();
                String owner=packages.packageOwner(before.selection().owner());
                called[0]=true;
                return ai.generate(owner,before.selection().name(),prepared.preview().source(),prepared.action());
            });
            boolean rejected=prepared.action()==Action.SQL&&!SelectAiReview.sqlResponse(text);
            outcome=new Outcome(id,prepared.action(),before,prepared.question(),started,elapsed(nanos),text,
                    rejected?UiMessages.text("aitest.notSql","SQL 생성 응답이 실행 가능한 SQL이 아닙니다. 원문을 확인해 주세요."):null,
                    rejected?SelectAiReview.responseCode(text):null,rejected?"sql-response":"complete",prepared.evidence());
        }catch(RuntimeException ex){
            // Do not expose Oracle/provider messages which can contain questions or secret response data.
            String error=!called[0]&&ex instanceof AiAssistant.Failure?ex.getMessage():UiMessages.text(called[0]?"aitest.callError":"aitest.preflightError",called[0]?
                    "응답을 확인하지 못했습니다. 사용량이 발생했을 수 있으며 자동 재시도하지 않았습니다.":
                    "호출 전 프로필 확인 단계에서 중단했습니다. 프로필을 새로고침해 주세요.");
            outcome=new Outcome(id,prepared.action(),before,prepared.question(),started,elapsed(nanos),null,error,
                    CredentialCatalogRepository.error(ex),called[0]?"generate":"preflight",prepared.evidence());
        }finally{state.finish(outcome);}
        return outcome;
    }
    private long elapsed(long nanos){return (System.nanoTime()-nanos)/1_000_000;}
}
