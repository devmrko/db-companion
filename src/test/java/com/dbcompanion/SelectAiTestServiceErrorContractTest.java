package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.*;
import com.dbcompanion.model.SelectAiTest.Action;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.OntologyInquiry;
import com.dbcompanion.service.SelectAiTestService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Synthetic service paths: safe Oracle detail belongs in error, not persistence-facing code. */
class SelectAiTestServiceErrorContractTest {
    private int networkTimeout = 15_000;
    private int expectedTimeout = 300;
    private int expectedGenerationTimeout = 300;
    private int generationCalls;
    private String lastPrompt;
    private Action lastAction;
    private boolean generationSuccess;
    private boolean rollbackFails;
    private int executionCalls;
    private String executedSql;
    private boolean success;
    private Runnable duringExecution=()->{};
    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getAutoCommit" -> true;
            case "isClosed", "isReadOnly" -> false;
            case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
            case "getNetworkTimeout" -> networkTimeout;
            case "setNetworkTimeout" -> { networkTimeout = (int)args[1]; yield null; }
            case "rollback" -> { if(rollbackFails)throw new SQLException("ORA-17008: Closed connection", "08003", 17008); yield null; }
            case "createStatement" -> Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class[]{Statement.class}, (statement, statementMethod, statementArgs) ->
                    statementMethod.getName().equals("execute") ? false : statementMethod.getReturnType() == boolean.class ? false : statementMethod.getReturnType() == int.class ? 0 : null);
            default -> method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : null;
        });
    }
    private final SessionDataSource source = new SessionDataSource() { @Override public Connection getConnection() { return connection(); } };
    private final JdbcTemplate jdbc = new JdbcTemplate(source);
    private final JsonMapper json = new JsonMapper();
    private final AiAssistant.Selection selection = new AiAssistant.Selection("APP", "P");
    private final AiAssistant.Profile profile = new AiAssistant.Profile(selection, "oci", "model", "v1");
    private RuntimeException failure = new DataAccessResourceFailureException("customer secret must not be displayed", new SQLException("ORA-01031: insufficient privileges\ncustomer secret must not be displayed", "42000", 1031));
    private final AiAssistantRepository ai = new AiAssistantRepository(jdbc) {
        @Override public AiAssistant.Profile profile(AiAssistant.Selection value) { return profile; }
        @Override public String generate(String owner, String name, String prompt, Action action,int timeoutSeconds) {
            assertThat(timeoutSeconds).isEqualTo(expectedGenerationTimeout);
            assertThat(networkTimeout).isEqualTo((expectedGenerationTimeout+30)*1000);
            var holder=(org.springframework.jdbc.datasource.ConnectionHolder)org.springframework.transaction.support.TransactionSynchronizationManager.getResource(source);
            assertThat(holder).isNotNull();
            assertThat(holder.getTimeToLiveInSeconds()).isBetween(expectedGenerationTimeout+55,expectedGenerationTimeout+60);
            generationCalls++;
            lastPrompt=prompt;lastAction=action;
            if(generationSuccess)return "SELECT 1 FROM DUAL";
            throw failure;
        }
    };
    private final OntologyQueryRepository rows = new OntologyQueryRepository(jdbc, json) {
        @Override public OntologyInquiry.Rows execute(String id, String sql, String hash, String actor, int timeoutSeconds, Runnable reading) {
            assertThat(timeoutSeconds).isEqualTo(expectedTimeout);
            assertThat(networkTimeout).isEqualTo((expectedTimeout+30)*1000);
            var holder=(org.springframework.jdbc.datasource.ConnectionHolder)org.springframework.transaction.support.TransactionSynchronizationManager.getResource(source);
            assertThat(holder).isNotNull();
            assertThat(holder.getTimeToLiveInSeconds()).isBetween(expectedTimeout+55,expectedTimeout+60);
            executionCalls++;
            executedSql=sql;
            duringExecution.run();
            if(success){reading.run();return new OntologyInquiry.Rows(id,sql,hash,actor,Instant.now().toString(),List.of("N"),List.of(List.of(new OntologyInquiry.Cell("1",false))),false);}
            throw failure;
        }
    };
    private final SelectAiTestService service = new SelectAiTestService(source, ai, new DatabaseRepository(jdbc), new ProfileHistoryRepository(jdbc, json, null) {
        @Override public String packageOwner(String user) { return "CLOUD"; }
    }, rows, null, null, json);

    private PoolSession session() {
        var value = new PoolSession(new HikariDataSource(), "LOW", () -> {});
        value.initialize(new DatabaseSession(new DatabaseInfo("APP", "APP", "LOW", "DB"), List.of("APP")));
        value.metadata().aiTest().select(selection);
        return value;
    }
    private void assertContract(String code, String error, String stage) {
        assertThat(code).isEqualTo("ORA-01031").hasSizeLessThanOrEqualTo(128);
        assertThat(error).contains("Failed stage: " + stage, "Confirmed: Oracle returned the displayed code(s)", "Possible cause (not confirmed):")
                .doesNotContain("customer secret");
    }

    @Test void generationFailureKeepsShortCodeAndSafeExplainedError() {
        try (var session = session()) {
            var prepared = service.preview(session, Action.SQL, "question", false, null, java.util.Locale.ENGLISH);
            var result = service.run(session, prepared.preview().token(), true);
            assertContract(result.code(), result.error(), "AI request");
            assertThat(networkTimeout).isEqualTo(15_000);
            assertThat(generationCalls).isEqualTo(1);
            var progress=session.metadata().aiTest().progress().snapshots().getFirst();
            assertThat(progress.status()).isEqualTo(SelectAiProgress.Status.FAILED);
            assertThat(progress.steps().getLast().stage()).isEqualTo(SelectAiProgress.Stage.AI);
        }
    }
    @Test void reviewFailureKeepsShortCodeAndSafeExplainedError() {
        try (var session = session()) {
            session.metadata().aiTest().finish(new SelectAiTest.Outcome("prompt", Action.PROMPT, profile, "question", Instant.now(), 1, "prompt", null, null, "complete"));
            session.metadata().assistant().select(selection);
            var prepared = service.reviewPreview(session, "prompt", java.util.Locale.ENGLISH);
            var result = service.review(session, prepared.preview().token(), true);
            assertContract(result.code(), result.error(), "AI review request");
            assertThat(networkTimeout).isEqualTo(15_000);
            assertThat(generationCalls).isEqualTo(1);
        }
    }
    private SelectAiTest.ExecutionResult prepareExecutedResult(PoolSession session){
        String sql="SELECT 1 FROM DUAL";
        session.metadata().aiTest().finish(new SelectAiTest.Outcome("result",Action.SQL,profile,"q",Instant.now(),1,sql,null,null,"complete"));
        var result=new SelectAiTest.ExecutionResult("result",new OntologyInquiry.Rows("result",sql,com.dbcompanion.service.OntologyQueryService.hash(sql),"APP",Instant.now().toString(),List.of("N"),List.of(List.of(new OntologyInquiry.Cell("PRIVATE_CELL",false))),false),null,null,1);
        session.metadata().aiTest().finishExecution(result);session.metadata().assistant().select(selection);return result;
    }
    @Test void resultReviewCallsChatExactlyOnceAndNeverExecutesOrReplacesSql(){
        generationSuccess=true;
        try(var session=session()){
            var rowsBefore=prepareExecutedResult(session);var original=session.metadata().aiTest().latest();
            var preview=service.resultReviewPreview(session,"result","count unique users",java.util.Locale.ENGLISH);
            assertThat(generationCalls).isZero();assertThat(preview.source()).doesNotContain("PRIVATE_CELL");
            var result=service.resultReview(session,preview.token(),true);
            assertThat(result.error()).isNull();assertThat(result.sqlHash()).isEqualTo(rowsBefore.data().hash());
            assertThat(lastAction).isEqualTo(Action.CHAT);assertThat(lastPrompt).isEqualTo(preview.source());
            assertThat(session.metadata().aiTest().latest()).isSameAs(original);
            assertThat(session.metadata().aiTest().executionResult()).isSameAs(rowsBefore);
            assertThat(session.metadata().aiTest().running()).isFalse();assertThat(networkTimeout).isEqualTo(15000);
            assertThatThrownBy(()->service.resultReview(session,preview.token(),true)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(generationCalls).isEqualTo(1);assertThat(executionCalls).isZero();
        }
    }
    @Test void resultReviewFailurePreservesSafeErrorWithoutRetryOrSqlExecution(){
        try(var session=session()){
            prepareExecutedResult(session);
            var preview=service.resultReviewPreview(session,"result","",java.util.Locale.ENGLISH);
            var result=service.resultReview(session,preview.token(),true);
            assertContract(result.code(),result.error(),"AI result review request");
            assertThat(session.metadata().aiTest().running()).isFalse();assertThat(networkTimeout).isEqualTo(15000);
            assertThat(generationCalls).isEqualTo(1);assertThat(executionCalls).isZero();
        }
    }
    @Test void generationBudgetCoversEveryTestActionAndRestoresConnection(){
        generationSuccess=true;
        for(Action action:Action.values()){
            try(var session=session()){
                var prepared=service.preview(session,action,"question",false,null,java.util.Locale.ENGLISH);
                var result=service.run(session,prepared.preview().token(),true);
                assertThat(result.error()).isNull();
                assertThat(networkTimeout).isEqualTo(15_000);
                assertThatThrownBy(()->service.run(session,prepared.preview().token(),true)).isInstanceOf(AiAssistant.Failure.class);
            }
        }
        assertThat(generationCalls).isEqualTo(Action.values().length);
    }
    @Test void configuredGenerationBudgetReachesServiceIndependently(){
        expectedGenerationTimeout=600;generationSuccess=true;
        var configured=new SelectAiTestService(source,ai,null,new ProfileHistoryRepository(jdbc,json,null){
            @Override public String packageOwner(String user){return "CLOUD";}
        },rows,null,null,json,null,new com.dbcompanion.common.config.SelectAiExecutionSettings(120,600));
        try(var session=session()){
            var prepared=configured.preview(session,Action.SQL,"question",false,null,java.util.Locale.ENGLISH);
            assertThat(configured.run(session,prepared.preview().token(),true).error()).isNull();
            assertThat(configured.executionTimeoutSeconds()).isEqualTo(120);
            assertThat(networkTimeout).isEqualTo(15_000);
            assertThat(generationCalls).isEqualTo(1);
        }
    }
    @Test void generationTimeoutPreservesOriginalErrorWithoutRetry(){
        rollbackFails=true;
        failure=new org.springframework.dao.RecoverableDataAccessException("private request",new SQLException("ORA-18730: Interrupted IO error.: Socket read timed out","08006",18730));
        try(var session=session()){
            var prepared=service.preview(session,Action.SQL,"question",false,null,java.util.Locale.ENGLISH);
            var result=service.run(session,prepared.preview().token(),true);
            assertThat(result.code()).isEqualTo("ORA-18730");
            assertThat(result.error()).contains("AI request","ORA-18730","ORA-17008").doesNotContain("private request");
            assertThat(generationCalls).isEqualTo(1);
            assertThat(networkTimeout).isEqualTo(15_000);
            assertThat(session.metadata().aiTest().running()).isFalse();
        }
    }
    @Test void executionFailureKeepsShortCodeAndSafeExplainedError() {
        try (var session = session()) {
            session.metadata().aiTest().finish(new SelectAiTest.Outcome("sql", Action.SQL, profile, "question", Instant.now(), 1, "SELECT 1 FROM DUAL", null, null, "complete"));
            var preview = service.executionPreview(session, "sql");
            var result = service.execute(session, preview.token(), true);
            assertContract(result.code(), result.error(), "read-only SQL execution");
            assertThat(networkTimeout).isEqualTo(15_000);
            assertThat(executionCalls).isEqualTo(1);
            var progress=session.metadata().aiTest().progress().snapshots().getFirst();
            assertThat(progress.status()).isEqualTo(SelectAiProgress.Status.FAILED);
            assertThat(progress.steps().getLast().stage()).isEqualTo(SelectAiProgress.Stage.QUERY);
        }
    }
    @Test void configuredBudgetFlowsThroughServiceAndRestoresConnection(){
        expectedTimeout=600;success=true;
        var configured=new SelectAiTestService(source,ai,null,null,rows,null,null,json,null,
                new com.dbcompanion.common.config.SelectAiExecutionSettings(expectedTimeout));
        try(var session=session()){
            String sql="SELECT 1 FROM DUAL";
            session.metadata().aiTest().finish(new SelectAiTest.Outcome("sql",Action.SQL,profile,"question",Instant.now(),1,sql,null,null,"complete"));
            var preview=configured.executionPreview(session,"sql");
            var result=configured.execute(session,preview.token(),true);
            assertThat(configured.executionTimeoutSeconds()).isEqualTo(600);
            assertThat(result.error()).isNull();assertThat(executedSql).isEqualTo(sql);
            assertThat(executionCalls).isEqualTo(1);assertThat(networkTimeout).isEqualTo(15_000);
        }
    }
    @Test void timeoutFollowedByRollbackFailureStillReturnsOriginalCodeAndNeverRetries() {
        rollbackFails = true;
        failure = new org.springframework.dao.RecoverableDataAccessException("SQL [customer secret]", new SQLException("ORA-18730: Interrupted IO error.: Socket read timed out", "08006", 18730));
        try(var session = session()) {
            session.metadata().aiTest().finish(new SelectAiTest.Outcome("sql", Action.SQL, profile, "question", Instant.now(), 1, "SELECT 1 FROM DUAL", null, null, "complete"));
            var preview = service.executionPreview(session, "sql");
            var result = service.execute(session, preview.token(), true);
            assertThat(result.code()).isEqualTo("ORA-18730");
            assertThat(result.error()).contains("read-only SQL execution", "ORA-18730", "Socket read timed out", "ORA-17008").doesNotContain("customer secret");
            assertThat(result.data()).isNull();
            assertThat(executionCalls).isEqualTo(1);
            assertThat(networkTimeout).isEqualTo(15_000);
            assertThat(session.metadata().aiTest().running()).isFalse();
        }
    }
    @Test void oracleSyntaxErrorIsReturnedAfterSqlPassesThroughUnchanged(){
        failure = new org.springframework.jdbc.BadSqlGrammarException("query", "private sql", new SQLException("ORA-00904: invalid identifier", "42000", 904));
        String sql="WITH p AS (SELECT 7 N FROM DUAL) SELECT LEVEL, MISSING_COLUMN FROM p CONNECT BY LEVEL<=p.N";
        try(var session=session()){
            session.metadata().aiTest().finish(new SelectAiTest.Outcome("sql",Action.SQL,profile,"question",Instant.now(),1,sql,null,null,"complete"));
            var preview=service.executionPreview(session,"sql");
            var result=service.execute(session,preview.token(),true);
            assertThat(preview.sql()).isEqualTo(sql);
            assertThat(executedSql).isEqualTo(sql);
            assertThat(result.code()).isEqualTo("ORA-00904");
            assertThat(result.error()).contains("ORA-00904").doesNotContain("private sql");
            assertThat(executionCalls).isEqualTo(1);
        }
    }
    @Test void progressCanBeReadDuringExecutionAndAllSuccessfulStagesRemainVisible() throws Exception {
        success=true;var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        duringExecution=()->{entered.countDown();try{if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test did not release query");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}};
        try(var session=session();var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            session.metadata().aiTest().finish(new SelectAiTest.Outcome("sql",Action.SQL,profile,"question",Instant.now(),1,"SELECT 1 FROM DUAL",null,null,"complete"));
            var preview=service.executionPreview(session,"sql");String requestId=java.util.UUID.randomUUID().toString();
            var future=executor.submit(()->service.execute(session,preview.token(),true,requestId));
            try{assertThat(entered.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();var live=session.metadata().aiTest().progress().snapshots().getFirst();
                assertThat(live.id()).isEqualTo(requestId);assertThat(live.status()).isEqualTo(SelectAiProgress.Status.RUNNING);
                assertThat(live.steps().getLast().stage()).isEqualTo(SelectAiProgress.Stage.QUERY);
            }finally{release.countDown();}
            assertThat(future.get(1,java.util.concurrent.TimeUnit.SECONDS).error()).isNull();
            var finished=session.metadata().aiTest().progress().snapshots().getFirst();
            assertThat(finished.status()).isEqualTo(SelectAiProgress.Status.COMPLETE);
            assertThat(finished.steps()).extracting(SelectAiProgress.Step::stage).containsExactly(SelectAiProgress.Stage.CONNECTION,SelectAiProgress.Stage.PROFILE,SelectAiProgress.Stage.QUERY,SelectAiProgress.Stage.FETCH,SelectAiProgress.Stage.CLEANUP);
            assertThat(executionCalls).isEqualTo(1);
        }
    }
}
