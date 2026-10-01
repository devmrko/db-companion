package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.SqlCacheArchive.*;
import com.dbcompanion.repository.SqlCacheArchiveRepository;
import com.dbcompanion.service.SqlCacheArchiveService;
import com.dbcompanion.controller.SqlCacheArchiveController;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.*;

class SqlCacheArchiveTest {
    static Status state(String table,String job,boolean enabled,String access){return new Status("APP",table,job,enabled,"SCHEDULED","FREQ=SECONDLY;INTERVAL=60","next","last","SUCCEEDED","0",access);}
    @Test void registrationUsesFixedNamesAndStartsOnlyAfterCreation(){
        var sql=SqlCacheArchiveRepository.plan(Operation.INSTALL,60,state("MISSING","MISSING",false,"READY"));
        assertThat(sql).hasSize(4);assertThat(sql.getFirst()).startsWith("CREATE TABLE DBC_SQL_CACHE_ARCHIVE");
        assertThat(sql.get(2)).contains("enabled=>FALSE","auto_drop=>FALSE","FREQ=SECONDLY;INTERVAL=60");
        assertThat(sql.get(3)).contains("LOGGING_RUNS","ENABLE");
        assertThat(String.join("\n",sql)).doesNotContain("CREATE OR REPLACE","DROP ","GRANT ","GENERATE(");
    }
    @Test void partialInstallReusesOnlyRecognizedTable(){
        assertThat(SqlCacheArchiveRepository.plan(Operation.INSTALL,30,state("READY","MISSING",false,"READY"))).hasSize(2);
        assertThatThrownBy(()->SqlCacheArchiveRepository.plan(Operation.INSTALL,60,state("CONFLICT","MISSING",false,"READY"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SqlCacheArchiveRepository.plan(Operation.INSTALL,60,state("READY","READY",true,"READY"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void managementCannotOverwriteForeignJobsOrOperateWithoutSourceExceptPause(){
        for(var op:Operation.values())assertThatThrownBy(()->SqlCacheArchiveRepository.plan(op,60,state("READY","CONFLICT",true,"READY"))).isInstanceOf(IllegalArgumentException.class);
        var s=state("READY","READY",true,"ORA-00942");
        assertThat(SqlCacheArchiveRepository.plan(Operation.PAUSE,60,s).getFirst()).contains("DISABLE").doesNotContain("STOP_JOB","DROP");
        assertThatThrownBy(()->SqlCacheArchiveRepository.plan(Operation.RUN,60,s)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void intervalAndFiltersAreBoundedAndParameterized(){
        for(int n:new int[]{-1,0,9,3601})assertThatThrownBy(()->SqlCacheArchive.interval(n)).isInstanceOf(IllegalArgumentException.class);
        var q=new Query(LocalDate.of(2026,1,1),LocalDate.of(2026,1,31),"abc123abc1234","' OR 1=1 --",Match.ai,2);
        var s=SqlCacheArchiveRepository.listSql(q);
        assertThat(s.sql()).contains("DBC_SQL_CACHE_ARCHIVE","OFFSET ?","REGEXP_LIKE").doesNotContain("SYS.V_$SQL","' OR 1=1 --");
        assertThat(s.args()).contains("' OR 1=1 --",20,"2026-02-01");
        assertThatThrownBy(()->new Query(q.from(),q.to().plusDays(1),"","",Match.all,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SqlCacheArchive.key("' OR 1=1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void collectorDeduplicatesFullCursorIdentityWithoutAddingRepeatedCounters(){
        var sql=SqlCacheArchiveRepository.COLLECT;
        assertThat(sql).contains("MERGE INTO DBC_SQL_CACHE_ARCHIVE","SYS.V_$SQL","SYS_CONTEXT('USERENV','SESSION_USER')",
                "s.CON_ID","s.SQL_ID","s.CHILD_NUMBER","s.CHILD_ADDRESS","s.FIRST_LOAD_TIME","s.LAST_LOAD_TIME",
                "a.CURSOR_KEY = s.CURSOR_KEY","a.EXECUTIONS = s.EXECUTIONS","a.LAST_SEEN = SYSTIMESTAMP","ROLLBACK;","RAISE;");
        assertThat(sql).doesNotContain("a.EXECUTIONS +","DELETE FROM","WHEN MATCHED THEN INSERT","V_$SQL_BIND_CAPTURE");
        assertThat(sql.length()).isLessThan(4000);
        assertThat(SqlCacheArchiveRepository.helpScript()).contains("-- GRANT READ ON SYS.V_$SQL TO \"<LOGIN_USER>\";","CREATE TABLE DBC_SQL_CACHE_ARCHIVE","DBMS_SCHEDULER.CREATE_JOB","SELECT JOB_NAME");
    }
    @Test void collectionItselfIsRestrictedToSelectAiCallsNotOnlyTheUiFilter(){
        var sql=SqlCacheArchiveRepository.COLLECT;
        var source=sql.substring(sql.indexOf("USING ("),sql.indexOf(") s ON"));
        assertThat(source).contains("s.PARSING_SCHEMA_NAME = SYS_CONTEXT('USERENV','SESSION_USER')",
                "REGEXP_LIKE(","REGEXP_REPLACE(s.SQL_FULLTEXT", "select[[:space:]]+ai", "DBMS_CLOUD_AI", "GENERATE");
        assertThat(source).contains("INSTR(s.SQL_TEXT, 'DBC_SQL_ARCHIVE_V1') = 0");
        assertThat(SqlCacheArchiveRepository.helpScript()).contains(source.replace("'", "''"));
    }
    @Test void sourcePatternsRecognizeCallsAndRejectOrdinaryQueriesAndLiteralMentions()throws Exception{
        String sql=SqlCacheArchiveRepository.COLLECT;
        int maskStart=sql.indexOf("q'~")+3,maskEnd=sql.indexOf("~'",maskStart);
        String mask=sql.substring(maskStart,maskEnd);
        int callStart=sql.indexOf("'(^",maskEnd)+1,callEnd=sql.indexOf("',",callStart);
        String call=sql.substring(callStart,callEnd).replace("[:space:]","\\s").replace("[:alnum:]","a-zA-Z0-9");
        var masking=java.util.regex.Pattern.compile(mask,java.util.regex.Pattern.DOTALL);
        var matching=java.util.regex.Pattern.compile(call,java.util.regex.Pattern.CASE_INSENSITIVE);
        var resource=new org.springframework.core.io.ClassPathResource("sql-cache-archive-candidates.json");
        var fixtures=new tools.jackson.databind.json.JsonMapper().readTree(resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        int count=0;
        for(var row:fixtures){String candidate=row.get(1).asString();
            assertThat(matching.matcher(masking.matcher(candidate).replaceAll(" ")).find()).as("fixture %s",count).isEqualTo(row.get(0).asBoolean());count++;
        }
        assertThat(count).isGreaterThanOrEqualTo(25);
    }
    final SessionDataSource source=new SessionDataSource();
    Status current=state("MISSING","MISSING",false,"READY");int applies;
    final SqlCacheArchiveRepository repo=new SqlCacheArchiveRepository(new JdbcTemplate(source)){
        @Override public Status status(String owner){assertThat(owner).isEqualTo("APP");return current;}
        @Override public void apply(List<String> statements){applies++;}
        @Override public Page page(Query q){return new Page(List.of(),q.page(),false);}
    };
    final SqlCacheArchiveService service=new SqlCacheArchiveService(source,repo);
    PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));s.metadata().selectSchema("OTHER");return s;}
    @Test void serviceBindsLoginOwnerAndRejectsChangedOrExpiredPreview(){try(var s=session()){
        var p=service.preview(s,Operation.INSTALL,60);assertThat(applies).isZero();assertThat(p.owner()).isEqualTo("APP");
        current=state("MISSING","CONFLICT",false,"READY");assertThatThrownBy(()->service.apply(s,p)).isInstanceOf(IllegalArgumentException.class);
        current=state("MISSING","MISSING",false,"READY");
        var expired=new Preview(p.token(),p.operation(),p.seconds(),p.owner(),p.fingerprint(),Instant.EPOCH,p.statements());
        assertThatThrownBy(()->service.apply(s,expired)).isInstanceOf(IllegalArgumentException.class);assertThat(applies).isZero();
        service.apply(s,p);assertThat(applies).isEqualTo(1);
    }}
    @Test void readsStillWorkWithoutVsqlAndNeverInstall(){try(var s=session()){
        current=state("READY","READY",false,"ORA-00942");
        assertThat(service.page(s,new Query(LocalDate.now(),LocalDate.now(),"","",Match.all,1)).rows()).isEmpty();assertThat(applies).isZero();
    }}
    @Test void controllerRequiresConsentAndConsumesTokenOnce(){try(var s=session()){
        var r=new MockHttpServletRequest();var h=new MockHttpSession();r.setSession(h);h.setAttribute(PoolSession.ATTRIBUTE,s);
        var c=new SqlCacheArchiveController(service);var p=(Preview)c.preview(new SqlCacheArchiveController.Prepare(Operation.INSTALL,60),r).getBody();
        assertThat(c.apply(new SqlCacheArchiveController.Apply(p.token(),false),r).getStatusCode().value()).isEqualTo(400);assertThat(applies).isZero();
        assertThat(c.apply(new SqlCacheArchiveController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(200);assertThat(applies).isEqualTo(1);
        assertThat(c.apply(new SqlCacheArchiveController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(400);assertThat(applies).isEqualTo(1);
    }}
}
