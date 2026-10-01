package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.AppRecordRepository;
import com.dbcompanion.service.ProblemQuestionService;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

/** Captures synthetic observations without executing SQL, AI or database writes. */
class ProblemQuestionCaptureTest {
    final JsonMapper json = new JsonMapper(); JsonNode savedAttempt;
    final SessionDataSource source = new SessionDataSource() {@Override public Connection getConnection() {return proxy(Connection.class,(p,m,a)->switch(m.getName()){case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);default->empty(m.getReturnType());});}};
    final AppRecordRepository records = new AppRecordRepository(new JdbcTemplate(source), json) {@Override public String status(String schema,String login){return "READY";} @Override public void begin(String schema,String id,String type,JsonNode payload){if(ProblemQuestion.ATTEMPT_TYPE.equals(type))savedAttempt=payload;} @Override public boolean finish(String schema,String id,String state,JsonNode payload){return true;}};
    final ProblemQuestionService service = new ProblemQuestionService(source,records,json);
    final AiAssistant.Profile profile = new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"oci","model","v1"); final Instant time=Instant.parse("2026-01-01T00:00:00Z");
    private SelectAiTest.Outcome outcome(SelectAiTest.Action action,String text,String error,SelectAiEvidence.Snapshot evidence){return new SelectAiTest.Outcome("synthetic-result",action,profile,"question",time,10,text,error,error==null?null:"ORA-20400",error==null?null:"generate",evidence);}
    private SelectAiEvidence.Snapshot evidence(String hash){return new SelectAiEvidence.Snapshot("APP","question","route",List.of(),hash,"{}",List.of());}
    private PoolSession session(){var s=new OntologyReadCacheTest().session();s.metadata().aiTest().select(profile.selection());s.metadata().aiTest().finish(outcome(SelectAiTest.Action.SQL,"SELECT 1 FROM DUAL",null,null));return s;}
    private void save(PoolSession s){service.saveLatest(s,new ProblemQuestion.Create("question","problem","expected","",null),true);assertThat(savedAttempt).isNotNull();}
    @Test void promptFromDifferentOntologyEvidenceIsNotAttachedToSavedResult(){try(var s=session()){s.metadata().aiTest().finish(outcome(SelectAiTest.Action.SQL,"SELECT 1 FROM DUAL",null,evidence("old")));s.metadata().aiTest().finish(outcome(SelectAiTest.Action.PROMPT,"unrelated-prompt-marker",null,evidence("new")));save(s);assertThat(savedAttempt.path("promptSnapshot").path("availability").asString()).isNotEqualTo("RECONSTRUCTED_SHOWPROMPT");assertThat(savedAttempt.toString()).doesNotContain("unrelated-prompt-marker");}}
    @Test void erroredPromptWithPartialTextIsExplicitlyAnErrorNotAValidReconstruction(){try(var s=session()){s.metadata().aiTest().finish(outcome(SelectAiTest.Action.PROMPT,"partial response","synthetic failure",null));save(s);assertThat(savedAttempt.path("promptSnapshot").path("availability").asString()).contains("ERROR");}}
    @Test void loadedObjectListWithoutAnyTableReadsIsNotCapturedMetadata(){try(var s=session()){var inspection=SelectAiInspection.create(profile,"question",List.of(new AiProfileAttribute("object_list","[{\"owner\":\"APP\",\"name\":\"T\"}]")),time);s.metadata().aiTest().inspection(inspection);save(s);assertThat(savedAttempt.path("metadataSnapshot").path("availability").asString()).isEqualTo("NOT_QUERIED");}}
    @Test void observationReadAfterGenerationIsNotPresentedAsGenerationTimeSnapshot(){try(var s=session()){s.metadata().aiTest().inspection(SelectAiInspection.create(profile,"question",List.of(),time.plusSeconds(30)));save(s);assertThat(savedAttempt.path("optionSnapshot").path("availability").asString()).isNotEqualTo("CAPTURED");assertThat(savedAttempt.path("optionSnapshot").path("checkedAt").asString()).isEqualTo(time.plusSeconds(30).toString());}}
    @Test void fullPromptFitsWithoutDuplicatingTwoCopiesInTheSameRecord(){try(var s=session()){String fullPrompt="unique-prompt-prefix"+"x".repeat(200_000);s.metadata().aiTest().finish(outcome(SelectAiTest.Action.PROMPT,fullPrompt,null,null));save(s);assertThat(savedAttempt.path("promptSnapshot").path("value").asString()).isEqualTo(fullPrompt);assertThat(savedAttempt.toString().split("unique-prompt-prefix",-1)).hasSize(2);}}
    @Test void lazyMetadataUsesTableObservationTimeNotEarlierProfileReadTime(){try(var s=session()){
        var inspection=SelectAiInspection.create(profile,"question",List.of(new AiProfileAttribute("object_list","[{\"owner\":\"APP\",\"name\":\"T\"}]")),time.minusSeconds(60));
        var table=new SelectAiInspection.Table("APP","T","TABLE","synthetic",List.of(),List.of(),"DISABLED",null,time.plusSeconds(45));
        s.metadata().aiTest().inspection(SelectAiInspection.table(inspection,table));save(s);
        assertThat(savedAttempt.path("metadataSnapshot").path("checkedAt").asString()).isEqualTo(time.plusSeconds(45).toString());
        assertThat(savedAttempt.path("metadataSnapshot").path("availability").asString()).isEqualTo("POST_GENERATION_READ");
    }}
    @Test void lazyFeedbackDetailUsesLatestObservationTimeAndKeepsItsOwnTimestamp(){try(var s=session()){
        var inspection=SelectAiInspection.create(profile,"question",List.of(),time.minusSeconds(60));
        var rows=new AiFeedback.Page(List.of(new AiFeedback.Item("AAAAAAAAAAAAAAAAAA","question","positive","synthetic")),1,false);
        inspection=SelectAiInspection.feedback(inspection,new SelectAiInspection.Feedback("",1,rows,false,null,time.minusSeconds(30)));
        var detail=new AiFeedback.Detail("question","positive","response","synthetic",null,"SELECT 1 FROM DUAL",null);
        inspection=SelectAiInspection.detail(inspection,new SelectAiInspection.FeedbackDetail("AAAAAAAAAAAAAAAAAA",detail,time.plusSeconds(90)));
        s.metadata().aiTest().inspection(inspection);save(s);
        assertThat(savedAttempt.path("feedbackSnapshot").path("checkedAt").asString()).isEqualTo(time.plusSeconds(90).toString());
        assertThat(savedAttempt.path("feedbackSnapshot").path("availability").asString()).isEqualTo("POST_GENERATION_READ");
    }}
    @Test void preGenerationCatalogReadDoesNotClaimToBeProviderRequestCapture(){try(var s=session()){
        s.metadata().aiTest().inspection(SelectAiInspection.create(profile,"question",List.of(),time.minusSeconds(60)));save(s);
        assertThat(savedAttempt.path("optionSnapshot").path("availability").asString()).isEqualTo("PRE_GENERATION_READ");
    }}
    @Test void failedResultKeepsRawResponseAndCodePhaseWithoutPromotingItToSql(){try(var s=session()){
        s.metadata().aiTest().finish(outcome(SelectAiTest.Action.SQL,"provider raw response","safe failure",null));save(s);
        assertThat(savedAttempt.path("response").asString()).isEqualTo("provider raw response");assertThat(savedAttempt.path("sql").asString()).isEmpty();assertThat(savedAttempt.path("error").asString()).contains("safe failure","ORA-20400","generate");
    }}
    @Test void fullLengthOriginalQuestionAndValueBearingConditionsAreStoredSeparatelyWithoutDuplication(){try(var s=session()){
        String original="q".repeat(ProblemQuestion.MAX_TEXT);var confirmation=new SelectAiTest.ConditionConfirmation(original,"기간은?","2026년 1월",List.of(new SelectAiTest.ConfirmedCondition("집계 단위","일별"),new SelectAiTest.ConfirmedCondition("자유 입력 조건 1","취소 제외")));
        s.metadata().aiTest().finish(new SelectAiTest.Outcome("condition-result",SelectAiTest.Action.SQL,profile,original,time,10,"SELECT 1 FROM DUAL",null,null,"complete",null,confirmation));
        service.saveLatest(s,new ProblemQuestion.Create(original,"problem","expected","",null),true);
        assertThat(savedAttempt.path("input").asString()).isEqualTo(original);assertThat(savedAttempt.path("conditions").asString()).contains("confirmationQuestion","집계 단위","일별").doesNotContain("originalQuestion");
    }}
}
