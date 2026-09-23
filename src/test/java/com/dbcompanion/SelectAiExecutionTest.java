package com.dbcompanion;

import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.SelectAiTest;
import com.dbcompanion.repository.SelectAiExecutionRepository;
import com.dbcompanion.service.SelectAiReadSql;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SelectAiExecutionTest {
    @Test void acceptsOrdinarySelectCteSubqueryJoinsAggregatesAndWindow(){
        for(String sql:List.of(
                "select * from SALES",
                "SELECT t.ID, t.NAME FROM APP.CUSTOMERS t WHERE t.ID IN (1,2) ORDER BY t.ID",
                "WITH users AS (SELECT ID FROM APP.USERS WHERE ACTIVE=1) SELECT u.ID FROM users u JOIN APP.SALES s ON s.ID=u.ID",
                "SELECT s.ID, SUM(s.AMOUNT) TOTAL FROM SALES s GROUP BY s.ID HAVING SUM(s.AMOUNT)>0 ORDER BY TOTAL DESC",
                "SELECT x.ID FROM (SELECT ID FROM CUSTOMERS) x WHERE EXISTS (SELECT 1 FROM SALES s WHERE s.ID=x.ID)",
                "SELECT ROW_NUMBER() OVER (PARTITION BY GAME_ID ORDER BY DT) RN FROM SALES",
                "SELECT ID FROM USERS UNION ALL SELECT ID FROM SALES",
                "SELECT CASE WHEN AMOUNT>0 THEN 'positive' ELSE 'zero' END RESULT FROM SALES",
                "SELECT TO_CHAR(DT,'YYYY-MM-DD'), COALESCE(AMOUNT,0) FROM SALES",
                "SELECT 'DELETE; @remote 日本語 한국어' TEXT FROM DUAL",
                "SELECT 1 FROM DUAL;"))assertThat(SelectAiReadSql.check(sql).sql()).isNotBlank();
        assertThat(SelectAiReadSql.check("WITH q AS (SELECT ID FROM APP.T) SELECT * FROM q").tables()).containsExactly("APP.T");
        assertThat(SelectAiReadSql.check("SELECT * FROM \"App\".\"T\"").tables()).containsExactly("\"App\".\"T\"");
    }
    @Test void blocksWritesCallsLinksLockingMultipleStatementsAndUnsupportedSyntax(){
        for(String sql:List.of(
                "DELETE FROM T","UPDATE T SET X=1","CREATE TABLE T(X NUMBER)","BEGIN NULL; END;",
                "SELECT * INTO OTHER FROM T","SELECT * FROM T FOR UPDATE","SELECT * FROM T@REMOTE",
                "SELECT * FROM T; DELETE FROM T","WITH FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END; SELECT f FROM DUAL",
                "SELECT MY_FUNC(ID) FROM T","SELECT APP.MY_FUNC(ID) FROM T","SELECT \"MY_FUNC\"(ID) FROM T",
                "SELECT DBMS_CLOUD_AI.GENERATE('question') FROM DUAL","SELECT UTL_HTTP.REQUEST('url') FROM DUAL",
                "SELECT s.NEXTVAL FROM DUAL","SELECT s.\"CURRVAL\" FROM DUAL","SELECT \"NEXTVAL\" FROM DUAL",
                "SELECT * FROM TABLE(APP.F())","SELECT CAST(ID AS APP.MY_TYPE) FROM T","SELECT :value FROM DUAL",
                "SELECT * FROM T --comment","SELECT /*+ hint */ * FROM T","SELECT /* safe? */ * FROM T",
                "SELECT AI RUNSQL SELECT * FROM T","SELECT AI CHAT 'question' FROM DUAL",
                "WITH q AS (DELETE FROM T RETURNING ID) SELECT * FROM q"))
            assertThatThrownBy(()->SelectAiReadSql.check(sql)).as(sql).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void enforcesLengthDepthAndEmptyInput(){
        for(String sql:Arrays.asList(null,""," ","SELECT '"+"x".repeat(20000)+"' FROM DUAL","SELECT "+"(".repeat(33)+"1"+")".repeat(33)+" FROM DUAL"))
            assertThatThrownBy(()->SelectAiReadSql.check(sql)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void viewDependencyPolicyRejectsFunctionsPackagesTypesAndRemoteLinks(){
        for(String type:List.of("TABLE","VIEW","SYNONYM","MATERIALIZED VIEW"))assertThat(SelectAiExecutionRepository.permittedDependency(new SelectAiExecutionRepository.ObjectRef("APP","T",type,null))).isTrue();
        for(String type:List.of("FUNCTION","PACKAGE","TYPE","PROCEDURE","JAVA CLASS"))assertThat(SelectAiExecutionRepository.permittedDependency(new SelectAiExecutionRepository.ObjectRef("APP","T",type,null))).isFalse();
        assertThat(SelectAiExecutionRepository.permittedDependency(new SelectAiExecutionRepository.ObjectRef("APP","T","TABLE","REMOTE"))).isFalse();
    }
    final Instant now=Instant.parse("2026-09-21T06:00:00Z");
    final AiAssistant.Selection selected=new AiAssistant.Selection("APP","P");
    final AiAssistant.Profile profile=new AiAssistant.Profile(selected,"oci","model","version");
    SelectAiTest.State state(){var state=new SelectAiTest.State();state.select(selected);state.finish(new SelectAiTest.Outcome("id",SelectAiTest.Action.SQL,profile,"q",now,10,"SELECT 1 FROM DUAL",null,null,"complete"));return state;}
    SelectAiTest.ExecutionPreview prepare(SelectAiTest.State state){return state.prepareExecution("id",SelectAiReadSql.check("SELECT 1 FROM DUAL"),now);}
    @Test void executionNeedsConsentAndTokenIsOneUseEvenAfterFailure(){
        var state=state();var preview=prepare(state);
        assertThatThrownBy(()->state.consumeExecution(preview.token(),false,now)).isInstanceOf(AiAssistant.Failure.class);
        var run=state.consumeExecution(preview.token(),true,now);assertThat(run.preview().sql()).isEqualTo("SELECT 1 FROM DUAL");assertThat(state.running()).isTrue();
        state.finishExecution(new SelectAiTest.ExecutionResult("id",null,"failed","ORA-00942",10));
        assertThat(state.latest().text()).isEqualTo("SELECT 1 FROM DUAL");assertThat(state.executionResult().code()).isEqualTo("ORA-00942");
        assertThatThrownBy(()->state.consumeExecution(preview.token(),true,now)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void expiredChangedCancelledOrRegeneratedRequestsCannotExecute(){
        for(String change:List.of("expired","selected","cancelled","regenerated","newpreview","refresh")){
            var state=state();var preview=prepare(state);
            switch(change){case "selected"->state.select(null);case "cancelled"->state.cancel(preview.token());case "regenerated"->state.prepare("APP",profile,SelectAiTest.Action.SQL,"new question","ko",now);case "newpreview"->prepare(state);case "refresh"->state.profiles(true,List::of);}
            assertThatThrownBy(()->state.consumeExecution(preview.token(),true,change.equals("expired")?now.plusSeconds(600):now)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(state.running()).isFalse();
        }
    }
    @Test void executionAndGenerationAreMutuallyExclusive() throws Exception {
        var state=state();var preview=prepare(state);var count=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(6)){
            var tasks=new ArrayList<Future<?>>();for(int i=0;i<24;i++)tasks.add(pool.submit(()->{try{state.consumeExecution(preview.token(),true,now);count.incrementAndGet();}catch(AiAssistant.Failure expected){}}));
            for(var task:tasks)task.get();
        }
        assertThat(count).hasValue(1);assertThatThrownBy(()->state.prepare("APP",profile,SelectAiTest.Action.CHAT,"q","ko",now)).isInstanceOf(AiAssistant.Failure.class);
        state.cancel(preview.token());assertThat(state.running()).isTrue();state.finishExecution(null);assertThat(state.running()).isFalse();
    }
    @Test void generationConsentCannotSurviveExecutionPreparation(){
        var state=state();var generation=state.prepare("APP",profile,SelectAiTest.Action.CHAT,"q","ko",now);prepare(state);
        assertThatThrownBy(()->state.consume(generation.preview().token(),true,"APP",now)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void executionUsesStrictReadOnlyAndExistingBoundedResultReader() throws Exception {
        var source=Files.readString(Path.of("src/main/java/com/dbcompanion/service/SelectAiTestService.java"));
        assertThat(source).contains("strict.setEnforceReadOnly(true)","execute.setReadOnly(true)","executionRepository.verify(checked","ai.profile(value.profile().selection())");
        var repository=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/OntologyQueryRepository.java"));
        assertThat(repository).contains("setQueryTimeout(15)","setMaxRows(201)","rows.size()==200","getColumnCount()>40","str.length()>2000","size>1_000_000");
        var controller=Files.readString(Path.of("src/main/java/com/dbcompanion/controller/SelectAiTestController.java"));
        assertThat(controller).contains("record ExecutionPrepare(String resultId)","record Run(String token,boolean consent)").doesNotContain("record Execute(String sql");
        var check=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/SelectAiExecutionRepository.java"));
        assertThat(check).contains("SELECT TEXT_LENGTH,TEXT FROM SYS.ALL_VIEWS","REFERENCED_LINK_NAME","DATA_TYPE_OWNER IS NOT NULL","return SelectAiReadSql.check(text)");
    }
}
