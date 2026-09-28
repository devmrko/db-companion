package com.dbcompanion;

import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.SelectAiReview;
import com.dbcompanion.service.SelectAiReadSql;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Text-only tests: accepting a response never executes or certifies its SQL. */
class SelectAiQueryTextTest {
    @Test void sendsOracleDialectAndNamesWithoutAnApplicationAllowlist(){
        for(String sql:List.of(
                "SELECT LEVEL FROM DUAL CONNECT BY LEVEL <= 7",
                "SELECT ID FROM APP.T START WITH PARENT_ID IS NULL CONNECT BY PRIOR ID=PARENT_ID",
                "WITH bounds AS (SELECT 7 N FROM DUAL) SELECT p.N FROM bounds p",
                "SELECT p.ID, p.P, CROSS FROM APP.T p",
                "SELECT APP.CUSTOM_FUNCTION(ID) FROM APP.T",
                "SELECT ID FROM APP.T@REMOTE",
                "SELECT /*+ FIRST_ROWS(10) */ ID FROM APP.T",
                "-- query explanation\nSELECT q'[text; -- literal]' FROM DUAL",
                "SELECT CAST(ID AS VARCHAR2(40)) FROM APP.T",
                "SELECT * FROM JSON_TABLE('{}', '$' COLUMNS(ID NUMBER PATH '$.id'))",
                "SELECT * FROM APP.T PIVOT (SUM(AMOUNT) FOR KIND IN ('A','B'))",
                "SELECT * FROM APP.T FOR UPDATE")){
            assertThat(SelectAiReadSql.check(sql).sql()).as(sql).isEqualTo(sql);
            assertThat(SelectAiReview.sqlResponse(sql)).isTrue();
        }
    }
    @Test void parserFailureDoesNotPreventOracleFromReturningItsOwnError(){
        String sql="SELECT ORACLE_WILL_VALIDATE THIS SYNTAX";
        assertThat(SelectAiReadSql.check(sql).sql()).isEqualTo(sql);
        String script="SELECT 1 FROM DUAL; DELETE FROM APP.T";
        // The app never splits or runs scripts: the one JDBC statement reaches Oracle unchanged.
        assertThat(SelectAiReadSql.check(script).sql()).isEqualTo(script);
    }
    @Test void keepsEnvelopeLimitsAndDoesNotTurnQueryExecutionIntoACommandConsole(){
        for(String value:Arrays.asList(null,""," ","not SQL","DELETE FROM APP.T","CREATE TABLE T(ID NUMBER)",
                "BEGIN NULL; END;","SELECT AI CHAT question","/* explanation */ SELECT /* command */ AI RUNSQL question",
                "SELECT '\0' FROM DUAL","SELECT '"+"x".repeat(20_000)+"' FROM DUAL"))
            assertThatThrownBy(()->SelectAiReadSql.check(value)).as(value).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void onlyRemovesResponseFenceAndTerminalClientDelimiter(){
        assertThat(SelectAiReadSql.check("```sql\nSELECT ';' FROM DUAL;\n```").sql()).isEqualTo("SELECT ';' FROM DUAL");
        assertThat(SelectAiReadSql.check("/* explanation */ SELECT ID FROM APP.T;").sql()).isEqualTo("/* explanation */ SELECT ID FROM APP.T");
        String sql="WITH q AS (SELECT ID FROM APP.T) SELECT ID FROM q";
        assertThat(SelectAiReadSql.check(sql).sql()).isEqualTo(sql);
    }
}
