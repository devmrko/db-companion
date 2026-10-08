package com.dbcompanion;

import com.dbcompanion.model.AiSqlHistory.*;
import com.dbcompanion.repository.AiSqlCandidates;
import com.dbcompanion.repository.AiSqlCandidates.*;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;

class AiSqlCandidatesTest {
    private static final Anchor ANCHOR=new Anchor("abc123def4567","7","2","APP","2026-01-01 12:00:00");
    private static Selection selection(Source source) {
        return new Selection(new Query(source,LocalDate.of(2026,1,1),LocalDate.of(2026,1,1),"","","",1),
                new Item(UUID.randomUUID().toString(),ANCHOR.time(),ANCHOR.sqlId(),"APP","GENERATE","5","call",
                        List.of(ANCHOR.sqlId(),"0","7","ABCDEF","2026-01-01/11:00:00")));
    }
    @Test void queriesBindScopeAndAnchorIdentityAndDoNotCarryTheAiCallFilter() {
        var anchor=AiSqlCandidates.anchorStatement(selection(Source.cache));
        assertThat(anchor.sql()).contains("CHILD_NUMBER=TO_NUMBER(?)","RAWTOHEX(s.CHILD_ADDRESS)=?","s.FIRST_LOAD_TIME=?","SYS_CONTEXT('USERENV','INSTANCE')","FETCH FIRST 2 ROWS ONLY");
        assertThat(anchor.args()).endsWith(ANCHOR.time());
        var nearby=AiSqlCandidates.nearbyStatement(ANCHOR);
        assertThat(nearby.sql()).contains("s.COMMAND_TYPE=3","s.CON_ID=TO_NUMBER(?)","s.PARSING_SCHEMA_NAME=?","1/1440","FETCH FIRST 41 ROWS ONLY","s.SQL_FULLTEXT")
                .doesNotContain("DBMS_CLOUD_AI","REGEXP_LIKE","SUBSTR");
        assertThat(nearby.args()).contains("APP",ANCHOR.sqlId());
        var ash=AiSqlCandidates.evidenceStatement(ANCHOR,List.of("123456789abcd"));
        assertThat(ash.sql()).contains("TOP_LEVEL_SQL_ID=? AND SQL_ID=?","c.USER_ID=a.USER_ID","c.SESSION_SERIAL#=a.SESSION_SERIAL#",
                "c.QC_INSTANCE_ID=a.INST_ID","c.QC_SESSION_SERIAL#=a.SESSION_SERIAL#","c.SQL_EXEC_START>=a.SQL_EXEC_START","c.CON_ID=TO_NUMBER(?)")
                .doesNotContain("DBA_HIST","SQL_BINDS","ADMIN");
        for(var stmt:List.of(anchor,nearby,ash)) assertThat(stmt.sql().chars().filter(c->c=='?').count()).isEqualTo(stmt.args().size());
        assertThatThrownBy(()->AiSqlCandidates.anchorStatement(selection(Source.audit))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AiSqlCandidates.evidenceStatement(ANCHOR,List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AiSqlCandidates.evidenceStatement(ANCHOR,Collections.nCopies(41,"id"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void displayFilterUsesSqlReferencesNotNamesInsideCommentsAndLiterals() {
        assertThat(AiSqlCandidates.businessObjects("SELECT 'FROM APP.SALES' FROM SYS.DUAL /* JOIN APP.X */")).isEmpty();
        assertThat(AiSqlCandidates.businessObjects("SELECT * FROM SYS.ALL_TABLES")).isEmpty();
        assertThat(AiSqlCandidates.businessObjects("SELECT * FROM APP.DBC_CONFIG")).isEmpty();
        assertThat(AiSqlCandidates.businessObjects("BEGIN null; END;")).isEmpty();
        assertThat(AiSqlCandidates.businessObjects("SELECT "+"x".repeat(21_000))).isEmpty();
        assertThat(AiSqlCandidates.businessObjects("WITH c AS (SELECT * FROM APP.SALES) SELECT * FROM c UNION ALL SELECT * FROM APP.REFUNDS"))
                .containsExactly("APP.REFUNDS","APP.SALES");
    }
    private static SqlCandidate candidate(String id,long offset) {
        return new SqlCandidate(id,"SELECT * FROM APP.SALES",List.of("APP.SALES"),ANCHOR.time(),offset,"1","0","TIME_SCHEMA");
    }
    @Test void sessionEvidenceRanksBeforeProximityButNeverAsConfirmedResult() {
        var ranked=AiSqlCandidates.rank(List.of(candidate("close",1),candidate("parallel",30),candidate("session",-10)),Map.of("parallel","COORDINATOR","session","SESSION"));
        assertThat(ranked).extracting(SqlCandidate::sqlId).containsExactly("session","parallel","close");
        assertThat(ranked).extracting(SqlCandidate::evidence).containsExactly("SESSION","COORDINATOR","TIME_SCHEMA");
    }
    @Test void topThreeAreDistinctAndOptionalPermissionFailureDoesNotHideCandidates() {
        var jdbc=new FakeJdbc();jdbc.error=1031;
        jdbc.rows.add(row("alpha",1));jdbc.rows.add(row("alpha",2));jdbc.rows.add(row("beta",3));jdbc.rows.add(row("gamma",4));jdbc.rows.add(row("delta",5));
        var result=new AiSqlCandidates(jdbc).find(selection(Source.cache));
        assertThat(result.items()).extracting(SqlCandidate::sqlId).containsExactly("alpha","beta","gamma");
        assertThat(result.sessionEvidence()).isEqualTo("UNAVAILABLE");assertThat(result.limited()).isTrue();
        assertThat(result.items()).allMatch(c->c.evidence().equals("TIME_SCHEMA"));
        assertThat(result.items().getFirst().sql()).isEqualTo("SELECT * FROM APP.SALES");
    }
    @Test void timeoutAndMissingSamplesDoNotClaimPermissionFailureOrConfirmedRelation() {
        for(int code:List.of(0,1013,942)) {
            var jdbc=new FakeJdbc();jdbc.error=code;jdbc.rows.add(row("alpha",1));
            var result=new AiSqlCandidates(jdbc).find(selection(Source.cache));
            assertThat(result.sessionEvidence()).isEqualTo(code==0?"NO_MATCH":code==942?"UNAVAILABLE":"UNCONFIRMED");
            assertThat(result.items()).hasSize(1);
        }
    }
    @Test void changedOrExpiredAnchorIsNotSilentlyReplacedAndAmbiguityIsRejected() {
        var jdbc=new FakeJdbc();jdbc.anchors=0;
        assertThat(new AiSqlCandidates(jdbc).find(selection(Source.cache))).isNull();assertThat(jdbc.reads).isEqualTo(1);
        jdbc.anchors=2;assertThatThrownBy(()->new AiSqlCandidates(jdbc).find(selection(Source.cache))).isInstanceOf(AmbiguousRecord.class);
    }
    @Test void noEligibleCandidatesSkipsAshAndDisclosesOmissions() {
        var jdbc=new FakeJdbc();jdbc.rows.add(new Object[]{"catalog","SELECT * FROM SYS.ALL_TABLES",ANCHOR.time(),1L,"0","0"});
        var result=new AiSqlCandidates(jdbc).find(selection(Source.cache));
        assertThat(result.items()).isEmpty();assertThat(result.omitted()).isEqualTo(1);
        assertThat(result.sessionEvidence()).isEqualTo("NOT_CHECKED");assertThat(jdbc.ashReads).isZero();
    }
    private static Object[] row(String id,long offset) { return new Object[]{id,"SELECT * FROM APP.SALES",ANCHOR.time(),offset,"1","0"}; }
    private static ResultSet result(Object[] values) {
        return (ResultSet)Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class[]{ResultSet.class},(p,m,args)->{
            Object value=values[(Integer)args[0]-1];
            return switch(m.getName()) { case "getString"->Objects.toString(value,null);case "getLong"->((Number)value).longValue();case "getInt"->((Number)value).intValue();default->throw new UnsupportedOperationException(m.getName()); };
        });
    }
    private static final class FakeJdbc extends JdbcTemplate {
        int anchors=1,reads,ashReads,error; final List<Object[]> rows=new ArrayList<>();
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args) {
            reads++;var values=sql.contains("FETCH FIRST 2 ROWS ONLY")?Collections.nCopies(anchors,new Object[]{ANCHOR.sqlId(),ANCHOR.container(),ANCHOR.instance(),ANCHOR.schema(),ANCHOR.time()}):rows;
            var out=new ArrayList<T>();try{for(var value:values)out.add(mapper.mapRow(result(value),out.size()));}catch(SQLException ex){throw new AssertionError(ex);}return out;
        }
        @Override public void query(String sql,RowCallbackHandler callback,Object...args) {
            ashReads++;if(error!=0)throw new DataAccessResourceFailureException("synthetic",new SQLException("test","72000",error));
        }
    }
}
