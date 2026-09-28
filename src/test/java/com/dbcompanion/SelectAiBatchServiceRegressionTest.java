package com.dbcompanion;
import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.SelectAiBatchService;
import com.dbcompanion.service.ProblemQuestionService;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic orchestration only: all repository/provider work is stubbed. */
class SelectAiBatchServiceRegressionTest {
    final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);default->empty(m.getReturnType());});}};
    final JdbcTemplate jdbc=new JdbcTemplate(source);final JsonMapper json=new JsonMapper();
    final String first="11111111-1111-4111-8111-111111111111",second="22222222-2222-4222-8222-222222222222";
    final Instant time=Instant.parse("2026-01-01T00:00:00Z");Instant parentVersion=time;
    AiAssistant.Profile profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"oci","model","attributes-v1");
    int calls;boolean failFirst;RuntimeException providerFailure;String response="SELECT 1 FROM DUAL";
    final List<String> sent=new ArrayList<>();
    final AppRecordRepository records=new AppRecordRepository(jdbc,json){
        @Override public void require(String schema,String login){assertThat(schema).isEqualTo(login);}
        @Override public ProblemQuestion.Detail problemDetail(String schema,String id){return new ProblemQuestion.Detail(new ProblemQuestion.Parent(1,id,"server question "+id,"problem","expected","",ProblemQuestion.Status.RECEIVED,false,time,parentVersion),List.of());}
    };
    final AiAssistantRepository ai=new AiAssistantRepository(jdbc){
        @Override public AiAssistant.Profile profile(AiAssistant.Selection value){return profile;}
        @Override public String generate(String owner,String name,String prompt,SelectAiTest.Action action){
            assertThat(action).isEqualTo(SelectAiTest.Action.SQL);assertThat(name).isEqualTo("P");calls++;sent.add(prompt);
            if(providerFailure!=null)throw providerFailure;
            if(failFirst&&calls==1)throw new IllegalStateException("synthetic provider failure");return response;
        }
    };
    final ProfileHistoryRepository packages=new ProfileHistoryRepository(jdbc,json,null){@Override public String packageOwner(String user){return "CLOUD_PKG";}};
    final SelectAiBatchService service=new SelectAiBatchService(records,ai,packages,source,new ProblemQuestionService(source,records,json));
    final class RecordingProblems extends ProblemQuestionService {
        int saves; boolean resultUnknown;
        RecordingProblems(){super(source,records,json);}
        @Override public String saveAttempt(PoolSession s,ProblemQuestion.Attempt attempt,Instant expectedParentVersion){
            saves++;if(resultUnknown)throw new IllegalStateException("synthetic unknown persistence outcome");return "attempt-"+saves;
        }
    }
    private SelectAiBatchService savingService(RecordingProblems problems){return new SelectAiBatchService(records,ai,packages,source,problems);}
    private PoolSession session(){return new OntologyReadCacheTest().session();}
    @Test void generateUsesOnlyServerQuestionAndOneShowsqlCall(){try(var s=session()){
        var p=service.prepare(s,List.of(first),"P");
        assertThat(p.profile()).isEqualTo(profile);
        var i=service.run(s,p.generation(),p.items().getFirst().token(),true);
        assertThat(service.status(s).profile()).isEqualTo(profile);
        assertThat(i.status()).isEqualTo(SelectAiBatch.Status.SUCCEEDED);assertThat(calls).isEqualTo(1);
        assertThat(sent.getFirst()).contains("server question "+first);assertThat(s.metadata().aiTest().latest()).isNull();
    }}
    @Test void unchangedModelButChangedProfileAttributesStopsBeforeProvider(){try(var s=session()){
        var p=service.prepare(s,List.of(first,second),"P");profile=new AiAssistant.Profile(profile.selection(),profile.provider(),profile.model(),"attributes-v2");
        var i=service.run(s,p.generation(),p.items().getFirst().token(),true);
        assertThat(calls).isZero();assertThat(i.status()).isEqualTo(SelectAiBatch.Status.FAILED);
        assertThat(s.metadata().aiBatch().plan().items().get(1).status()).isEqualTo(SelectAiBatch.Status.SKIPPED);
    }}
    @Test void changedParentStopsBeforeProvider(){try(var s=session()){
        var p=service.prepare(s,List.of(first),"P");parentVersion=time.plusSeconds(5);
        var i=service.run(s,p.generation(),p.items().getFirst().token(),true);
        assertThat(calls).isZero();assertThat(i.status()).isEqualTo(SelectAiBatch.Status.FAILED);
    }}
    @Test void returnedApologyIsNotSuccessfulGeneratedSql(){try(var s=session()){
        var p=service.prepare(s,List.of(first),"P");response="Sorry. Exception encountered: ORA-00904: invalid identifier";
        var i=service.run(s,p.generation(),p.items().getFirst().token(),true);
        assertThat(i.status()).isEqualTo(SelectAiBatch.Status.FAILED);assertThat(i.result()).isEqualTo(response);assertThat(calls).isEqualTo(1);
    }}
    @Test void consentReplayAndCancelDoNotMakeAdditionalCalls(){try(var s=session()){
        var p=service.prepare(s,List.of(first,second),"P");String token=p.items().getFirst().token();
        assertThatThrownBy(()->service.run(s,p.generation(),token,false)).isInstanceOf(AiAssistant.Failure.class);assertThat(calls).isZero();
        service.run(s,p.generation(),token,true);
        assertThatThrownBy(()->service.run(s,p.generation(),token,true)).isInstanceOf(AiAssistant.Failure.class);
        service.cancel(s,p.generation());
        assertThatThrownBy(()->service.run(s,p.generation(),p.items().get(1).token(),true)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(calls).isEqualTo(1);
    }}
    @Test void partialProviderFailureRemainsVisibleAndDoesNotRetry(){try(var s=session()){
        var p=service.prepare(s,List.of(first,second),"P");failFirst=true;
        assertThat(service.run(s,p.generation(),p.items().getFirst().token(),true).status()).isEqualTo(SelectAiBatch.Status.FAILED);
        assertThat(service.run(s,p.generation(),p.items().get(1).token(),true).status()).isEqualTo(SelectAiBatch.Status.SUCCEEDED);
        assertThat(calls).isEqualTo(2);assertThat(s.metadata().aiBatch().plan().items().getFirst().error()).isNotBlank();
    }}
    @Test void providerFailureKeepsSanitizedOracleCodeAndGeneratePhase(){try(var s=session()){
        providerFailure=new IllegalStateException(new SQLException("synthetic", "28000",1017));
        var p=service.prepare(s,List.of(first),"P");
        var item=service.run(s,p.generation(),p.items().getFirst().token(),true);
        assertThat(item.status()).isEqualTo(SelectAiBatch.Status.FAILED);
        assertThat(item.error()).contains("provider-error","ORA-01017").doesNotContain("synthetic");
    }}
    @Test void saveRequiresSeparateConsentAndNeverWritesWithoutIt(){try(var s=session()){
        var problems=new RecordingProblems();var saving=savingService(problems);var p=saving.prepare(s,List.of(first),"P");
        saving.run(s,p.generation(),p.items().getFirst().token(),true);
        assertThatThrownBy(()->saving.save(s,p.generation(),p.items().getFirst().token(),false)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(problems.saves).isZero();
    }}
    @Test void changedParentPreventsSaveBeforePersistence(){try(var s=session()){
        var problems=new RecordingProblems();var saving=savingService(problems);var p=saving.prepare(s,List.of(first),"P");
        saving.run(s,p.generation(),p.items().getFirst().token(),true);parentVersion=time.plusSeconds(9);
        assertThatThrownBy(()->saving.save(s,p.generation(),p.items().getFirst().token(),true)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(problems.saves).isZero();
    }}
    @Test void successfulSaveReplaysSameIdWithoutSecondWrite(){try(var s=session()){
        var problems=new RecordingProblems();var saving=savingService(problems);var p=saving.prepare(s,List.of(first),"P");
        saving.run(s,p.generation(),p.items().getFirst().token(),true);
        String firstSave=saving.save(s,p.generation(),p.items().getFirst().token(),true);
        assertThat(saving.save(s,p.generation(),p.items().getFirst().token(),true)).isEqualTo(firstSave);
        assertThat(problems.saves).isEqualTo(1);
    }}
    @Test void unknownSaveOutcomeBecomesOneWayUnconfirmedWithoutRetry(){try(var s=session()){
        var problems=new RecordingProblems();problems.resultUnknown=true;var saving=savingService(problems);var p=saving.prepare(s,List.of(first),"P");
        saving.run(s,p.generation(),p.items().getFirst().token(),true);
        assertThatThrownBy(()->saving.save(s,p.generation(),p.items().getFirst().token(),true)).hasMessageContaining("SAVE_UNCONFIRMED");
        assertThatThrownBy(()->saving.save(s,p.generation(),p.items().getFirst().token(),true)).hasMessageContaining("SAVE_UNCONFIRMED");
        assertThat(problems.saves).isEqualTo(1);
    }}
}
