package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.service.SelectAiSqlReferences;
import com.dbcompanion.model.SelectAiTest.Action;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SelectAiSqlReferencesTest {
    static final String SQL="""
        WITH standard_au AS (
          SELECT COUNT(DISTINCT GUID) standard_au_count FROM DEMO_APP.DEMO_NATIVE_GAME_USER_CTAS_BUILD
          WHERE BASE_DT=DATE '2026-08-03' AND AU_FLAG=1 AND EXPT_USER_YN='N'
        ), business_au AS (
          SELECT COUNT(DISTINCT GUID) business_au_count FROM DEMO_APP.DEMO_NATIVE_GAME_BIZ_USER
          WHERE BASE_DT=DATE '2026-08-03' AND TRIM(BIZ_AU_FLAG)='1' AND EXPT_USER_YN='N'
        ) SELECT s.standard_au_count,b.business_au_count FROM standard_au s CROSS JOIN business_au b;
        """;
    @Test void actualCteQueryReportsBothBaseTablesNotCteNames(){
        var result=SelectAiSqlReferences.analyze(Action.SQL,SQL);
        assertThat(result.status()).isEqualTo("PARSED");assertThat(result.source()).isEqualTo("RESPONSE");
        assertThat(result.tables()).extracting("name").containsExactlyInAnyOrder("DEMO_NATIVE_GAME_USER_CTAS_BUILD","DEMO_NATIVE_GAME_BIZ_USER");
        assertThat(result.tables()).allMatch(t->"DEMO_APP".equals(t.owner()));
    }
    @Test void commentsLiteralsAliasesAndNestedReferencesAreSeparated(){
        var result=SelectAiSqlReferences.analyze(Action.SQL,"""
            /* FROM APP.NOT_USED */ SELECT 'FROM APP.NOT_USED' label,
            (SELECT MAX(x.ID) FROM APP.INNER_T x) latest FROM APP.OUTER_T o
            JOIN APP.JOIN_T j ON j.ID=o.ID WHERE EXISTS (SELECT 1 FROM APP.INNER_T i WHERE i.ID=o.ID)
            -- JOIN APP.NOT_USED
            """);
        assertThat(result.status()).isEqualTo("PARSED");
        assertThat(result.tables()).extracting("name").containsExactlyInAnyOrder("INNER_T","OUTER_T","JOIN_T");
    }
    @Test void quotedIdentifiersRemainCaseSensitiveAndUnqualifiedNamesStayUnresolved(){
        var result=SelectAiSqlReferences.analyze(Action.SQL,"SELECT * FROM \"MiXeD\".\"A.B\" a JOIN local_t l ON 1=1");
        assertThat(result.status()).isEqualTo("PARSED");assertThat(result.tables()).anyMatch(t->"MiXeD".equals(t.owner())&&"A.B".equals(t.name()));
        assertThat(result.tables()).anyMatch(t->t.owner()==null&&t.name().equals("LOCAL_T"));
        assertThat(SelectAiSqlReferences.analyze(Action.SQL,"SELECT * FROM app.t a JOIN APP.T b ON 1=1").tables()).hasSize(1);
    }
    @Test void knownOracleRefusalReportsSqlReferencesButPreservesErrorAndOriginalText(){
        String wrapped="Sorry, unfortunately a valid SELECT statement could not be generated for your natural language prompt with the restriction of enforcing the use of object list. Here is some more information to help you further:\n\n"+SQL+"\nException encountered: ORA-20004: SQL statement references one or more objects not specified in the object_list of AI profile.";
        var profile=new AiAssistant.Profile(new AiAssistant.Selection("DEMO_APP","P"),"oci","m","v");
        var outcome=new SelectAiTest.Outcome("r",Action.SQL,profile,"q",Instant.now(),1,wrapped,"rejected","ORA-20004","sql-response");
        assertThat(outcome.sqlReferences().source()).isEqualTo("REJECTED_RESPONSE");assertThat(outcome.sqlReferences().status()).isEqualTo("PARSED");
        assertThat(outcome.sqlReferences().tables()).hasSize(2);assertThat(outcome.text()).isEqualTo(wrapped);assertThat(outcome.error()).isEqualTo("rejected");
        assertThat(SelectAiReview.sqlResponse(outcome.text())).isFalse();
        var json=JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(outcome));
        assertThat(json.get("sqlReferences").get("tables").size()).isEqualTo(2);assertThat(json.get("error").asString()).isEqualTo("rejected");
    }
    @Test void unsupportedNarrativeAndMultipleStatementsNeverCreateFalseReferences(){
        for(String sql:new String[]{"Here is an example: SELECT * FROM APP.T","SELECT * FROM APP.T; SELECT * FROM APP.SECRET","DELETE FROM APP.T","SELECT * FROM APP.T@REMOTE","SELECT FROM","Sorry, unfortunately a valid SELECT statement could not be generated.\nSELECT * FROM APP.T","x".repeat(20001)}){
            var result=SelectAiSqlReferences.analyze(Action.SQL,sql);assertThat(result.status()).as(sql.substring(0,Math.min(60,sql.length()))).isEqualTo("UNSUPPORTED");assertThat(result.tables()).isEmpty();
        }
    }
    @Test void markdownNoFromAndNonSqlActionsKeepDistinctStates(){
        assertThat(SelectAiSqlReferences.analyze(Action.SQL,"```sql\n"+SQL+"```").tables()).hasSize(2);
        var noFrom=SelectAiSqlReferences.analyze(Action.SQL,"SELECT 1");assertThat(noFrom.status()).isEqualTo("PARSED");assertThat(noFrom.tables()).isEmpty();
        assertThat(SelectAiSqlReferences.analyze(Action.PROMPT,SQL).status()).isEqualTo("NOT_SQL");
        assertThat(SelectAiSqlReferences.analyze(Action.CHAT,SQL).tables()).isEmpty();
        assertThat(SelectAiSqlReferences.analyze(Action.SQL,null).status()).isEqualTo("UNAVAILABLE");
    }
    @Test void cteChainsCaseAndQualifiedBaseTablesWithSameNameAreNotConfused(){
        for(String sql:new String[]{
                "WITH q AS (SELECT * FROM APP.T) SELECT * FROM Q",
                "WITH q AS (SELECT * FROM APP.T), r AS (SELECT * FROM q) SELECT * FROM r",
                "WITH q AS (SELECT * FROM APP.T) SELECT * FROM q UNION ALL SELECT * FROM APP.T"}){
            var result=SelectAiSqlReferences.analyze(Action.SQL,sql);assertThat(result.status()).isEqualTo("PARSED");
            assertThat(result.tables()).extracting("sqlName").as(sql).containsExactly("APP.T");
        }
        var collision=SelectAiSqlReferences.analyze(Action.SQL,"WITH q AS (SELECT * FROM APP.T) SELECT * FROM APP.q");
        assertThat(collision.tables()).extracting("name").containsExactlyInAnyOrder("T","Q");
        var scoped=SelectAiSqlReferences.analyze(Action.SQL,"SELECT * FROM q WHERE EXISTS (WITH q AS (SELECT * FROM APP.T) SELECT * FROM q)");
        assertThat(scoped.tables()).extracting("name").containsExactlyInAnyOrder("T","Q");
        var quoted=SelectAiSqlReferences.analyze(Action.SQL,"WITH \"MiXeD\" AS (SELECT * FROM APP.T) SELECT * FROM \"MiXeD\" UNION ALL SELECT * FROM mixed");
        assertThat(quoted.tables()).extracting("name").containsExactlyInAnyOrder("T","MIXED");
    }
}
