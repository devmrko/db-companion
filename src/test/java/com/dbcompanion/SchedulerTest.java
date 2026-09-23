package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Scheduler.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.repository.SchedulerRepository.View;
import com.dbcompanion.service.SchedulerService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SchedulerTest {
    private Rows rows(String status,Map<String,String>... values){return new Rows(List.of(values),"SYS.USER_SCHEDULER_JOBS",status,"","2026-09-19T03:00:00Z");}
    private Rows jobs(){return rows("AVAILABLE",Map.of("JOB_NAME","ETL"));}
    private Detail detail(){return new Detail(rows("AVAILABLE",Map.of("JOB_TYPE","PLSQL_BLOCK","JOB_ACTION","BEGIN NULL; END;")),Rows.empty("NOT_APPLICABLE"),Rows.empty("NOT_APPLICABLE"),Rows.empty("NOT_APPLICABLE"),Rows.empty("NOT_APPLICABLE"));}
    private Page page(){return new Page(rows("AVAILABLE",Map.of("LOG_ID","30")),"20");}
    @Test void immutableRowsKeepNullsAndFullSourceWithoutTranslating(){
        var map=new LinkedHashMap<String,String>();map.put("COMMENTS",null);map.put("JOB_ACTION","begin\n  null; -- 한글\nend;");var result=rows("AVAILABLE",map);map.put("JOB_ACTION","changed");
        assertThat(result.first()).containsEntry("COMMENTS",null).containsEntry("JOB_ACTION","begin\n  null; -- 한글\nend;");
        assertThatThrownBy(()->result.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->result.first().put("X","Y")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void inlineAndProgramActionsUseTheirOwnSourceAndOwner(){
        assertThat(Scheduler.action("APP",detail())).isEqualTo(new Action("APP","PLSQL_BLOCK","BEGIN NULL; END;","AVAILABLE"));
        var job=rows("AVAILABLE",Map.of("PROGRAM_OWNER","CODE_OWNER","PROGRAM_NAME","P","JOB_TYPE","PLSQL_BLOCK","JOB_ACTION","wrong"));
        var program=rows("AVAILABLE",Map.of("PROGRAM_TYPE","STORED_PROCEDURE","PROGRAM_ACTION","PKG.RUN"));
        var linked=new Detail(job,program,Rows.empty("NOT_APPLICABLE"),Rows.empty("NOT_APPLICABLE"),Rows.empty("NOT_APPLICABLE"));
        assertThat(Scheduler.action("APP",linked)).isEqualTo(new Action("CODE_OWNER","STORED_PROCEDURE","PKG.RUN","AVAILABLE"));
        var denied=new Detail(job,Rows.empty("ACCESS_REQUIRED"),linked.schedule(),linked.arguments(),linked.programArguments());
        assertThat(Scheduler.action("APP",denied).status()).isEqualTo("ACCESS_REQUIRED");assertThat(Scheduler.action("APP",denied).text()).isEmpty();
    }
    @Test void pageUsesTenPlusOneAndKeepsLargeLogIdsAsStrings(){
        var values=new ArrayList<Map<String,String>>();for(int i=20;i>=10;i--)values.add(Map.of("LOG_ID","12345678901234567890"+i));
        var page=Page.from(new Rows(values,"SRC","AVAILABLE","","now"));assertThat(page.rows().items()).hasSize(10);assertThat(page.next()).isEqualTo("1234567890123456789011");
        assertThat(Page.from(rows("AVAILABLE",Map.of("LOG_ID","10"))).next()).isEmpty();
        assertThat(Page.from(rows("ERROR")).rows().status()).isEqualTo("ERROR");
    }
    @Test void jobNamesRemainBindValuesAndCursorsRejectSql(){
        Scheduler.name("Mixed \"ETL\"");Scheduler.name("' OR 1=1");Scheduler.cursor("");Scheduler.cursor("9".repeat(38));
        for(String invalid:List.of("0","-1","1.2","1 OR 1=1"," 1","9".repeat(39)))assertThatThrownBy(()->Scheduler.cursor(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Scheduler.name("X".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Scheduler.name("X\0Y")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cacheSeparatesSchemaJobAndLoginWithoutRepeatedLoads(){
        var state=new State();var calls=new AtomicInteger();
        for(String schema:List.of("A","B","A"))state.list(schema,false,()->{calls.incrementAndGet();return jobs();});assertThat(calls).hasValue(2);
        for(String job:List.of("ETL","OTHER","ETL"))state.detail("A",job,()->{calls.incrementAndGet();return detail();});assertThat(calls).hasValue(4);
        state.detail("B","ETL",()->{calls.incrementAndGet();return detail();});new State().detail("A","ETL",()->{calls.incrementAndGet();return detail();});assertThat(calls).hasValue(6);
    }
    @Test void refreshInvalidatesAllJobCachesBeforeLoaderAndNotOtherSchemas(){
        var state=new State();var original=state.list("OTHER",false,this::jobs);state.list("APP",false,this::jobs);
        state.detail("APP","ETL",this::detail);state.code("APP","ETL",()->new Code(Scheduler.action("APP",detail()),List.of(),"AVAILABLE","","now"));
        state.page("APP","ETL","",this::page);state.log("APP","ETL","30",this::jobs);
        assertThatThrownBy(()->state.list("APP",true,()->{throw new IllegalStateException("read failed");})).hasMessage("read failed");
        var calls=new AtomicInteger();state.list("APP",false,()->{calls.incrementAndGet();return jobs();});state.detail("APP","ETL",()->{calls.incrementAndGet();return detail();});
        state.code("APP","ETL",()->{calls.incrementAndGet();return new Code(Scheduler.action("APP",detail()),List.of(),"AVAILABLE","","now");});
        assertThatThrownBy(()->state.log("APP","ETL","30",this::jobs)).isInstanceOf(IllegalArgumentException.class);
        state.page("APP","ETL","",()->{calls.incrementAndGet();return page();});state.log("APP","ETL","30",()->{calls.incrementAndGet();return jobs();});assertThat(calls).hasValue(5);
        assertThat(state.list("OTHER",false,()->{throw new IllegalStateException();})).isSameAs(original);
    }
    @Test void onlyIssuedCursorAndListedRunCanBeReadWithinSameJob(){
        var state=new State();state.page("APP","ETL","",this::page);
        assertThat(state.page("APP","ETL","20",()->new Page(rows("AVAILABLE",Map.of("LOG_ID","19")),""))).isNotNull();
        assertThat(state.log("APP","ETL","19",this::jobs)).isNotNull();
        assertThatThrownBy(()->state.page("APP","OTHER","20",this::page)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->state.page("OTHER","ETL","20",this::page)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->state.page("APP","ETL","15",this::page)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->state.log("APP","ETL","18",this::jobs)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void transientErrorsAreNotCachedAndAccessStatusIsCachedUntilRefresh(){
        var state=new State();var calls=new AtomicInteger();
        for(int i=0;i<2;i++)state.list("APP",false,()->{calls.incrementAndGet();return rows("ERROR");});assertThat(calls).hasValue(2);
        for(int i=0;i<2;i++)state.list("APP",false,()->{calls.incrementAndGet();return rows("ACCESS_REQUIRED");});assertThat(calls).hasValue(3);
        assertThat(new Code(Scheduler.action("APP",detail()),List.of(),"LIMIT","","now").cacheable()).isFalse();
        var failed=new Detail(rows("ERROR"),detail().program(),detail().schedule(),detail().arguments(),detail().programArguments());assertThat(failed.cacheable()).isFalse();
    }
    @Test void realServiceCacheHitNeverBorrowsAConnectionAndRejectsStaleSchema(){
        var source=new SessionDataSource();var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source);var service=new SchedulerService(source,new SchedulerRepository(jdbc),new RoutineSourceRepository(jdbc));
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var login=new PoolSession(pool,"LOW",()->{})){
            var data=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));login.initialize(data);var state=data.scheduler();
            var list=state.list("APP",false,this::jobs);var detail=state.detail("APP","ETL",this::detail);var page=state.page("APP","ETL","",this::page);var log=state.log("APP","ETL","30",this::jobs);
            var code=state.code("APP","ETL",()->new Code(Scheduler.action("APP",detail),List.of(),"AVAILABLE","","now"));
            assertThat(service.list(login,"APP",false)).isSameAs(list);assertThat(service.detail(login,"APP","ETL")).isSameAs(detail);assertThat(service.code(login,"APP","ETL")).isSameAs(code);
            assertThat(service.runs(login,"APP","ETL","")).isSameAs(page);assertThat(service.run(login,"APP","ETL","30")).isSameAs(log);
            assertThatThrownBy(()->service.detail(login,"OTHER","ETL")).isInstanceOf(Failure.class);
            assertThatThrownBy(()->service.detail(login,"APP","NOT_LISTED")).isInstanceOf(Failure.class);assertThat(pool.getHikariPoolMXBean()).isNull();
        }
    }
    @Test void sqlUsesSeparateCatalogsExactOwnerAndJobBindingsAndOptionalProjections(){
        var columns=new AgentViewColumns(List.of("OWNER","JOB_NAME","STATE"));
        String own=SchedulerRepository.selectSql(View.JOBS,"USER",columns,List.of("JOB_NAME","COMMENTS"),false,"","",5001);
        assertThat(own).contains("SYS.USER_SCHEDULER_JOBS","NULL AS \"COMMENTS\"","FETCH FIRST 5001").doesNotContain("OWNER = ?","JOB_ACTION");
        String dba=SchedulerRepository.selectSql(View.JOBS,"DBA",columns,List.of("JOB_NAME"),true,"","",2);
        assertThat(dba).contains("OWNER = ? AND JOB_NAME = ?").doesNotContain("APP","ADMIN");
        assertThat(SchedulerRepository.viewName(View.PROGRAMS,false,true)).isEqualTo("SYS.ALL_SCHEDULER_PROGRAMS");
        assertThatThrownBy(()->SchedulerRepository.selectSql(View.JOBS,"UNSAFE",columns,List.of("JOB_NAME"),false,"","",10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SchedulerRepository.selectSql(View.JOBS,"DBA",columns,List.of("X;DROP"),false,"","",10)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void logsAreKeysetBoundedAndNoFullOutputIsReadUntilSelection(){
        var columns=new AgentViewColumns(List.of("OWNER","JOB_NAME","LOG_ID","STATUS","OUTPUT"));
        var page=SchedulerRepository.selectSql(View.JOB_RUN_DETAILS,"DBA",columns,List.of("LOG_ID","STATUS"),true,"50","",11);
        assertThat(page).contains("OWNER = ? AND JOB_NAME = ? AND LOG_ID < ? ORDER BY LOG_ID DESC FETCH FIRST 11").doesNotContain("OUTPUT","OFFSET","COUNT(","50");
        var detail=SchedulerRepository.selectSql(View.JOB_RUN_DETAILS,"USER",columns,List.of("LOG_ID","OUTPUT"),true,"","30",2);
        assertThat(detail).contains("JOB_NAME = ? AND LOG_ID = ?","\"OUTPUT\"").doesNotContain("OWNER = ?");
    }
    @Test void anydataIsOnlyFlaggedAndMissingRequiredColumnsFail(){
        var columns=new AgentViewColumns(List.of("PROGRAM_NAME","ARGUMENT_POSITION","DEFAULT_ANYDATA_VALUE"));
        String sql=SchedulerRepository.selectSql(View.PROGRAM_ARGS,"USER",columns,List.of("HAS_ANYDATA"),true,"","",5001);
        assertThat(sql).contains("CASE WHEN \"DEFAULT_ANYDATA_VALUE\" IS NULL THEN 'FALSE' ELSE 'TRUE' END AS HAS_ANYDATA").doesNotContain("GetVarchar2","GETVARCHAR2");
        assertThatThrownBy(()->SchedulerRepository.selectSql(View.JOBS,"USER",new AgentViewColumns(List.of()),List.of("JOB_NAME"),false,"","",5)).isInstanceOf(IllegalStateException.class);
    }
    @Test void implementationIsReadOnlyLazyAndUsesActualLoginBinding() throws Exception {
        String repository=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/SchedulerRepository.java"));
        assertThat(repository).doesNotContain("jdbc.update(","jdbc.execute(","DBMS_SCHEDULER.","BINARY_OUTPUT","BINARY_ERRORS","equals(\"ADMIN\")");
        String service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/SchedulerService.java"));
        assertThat(service).contains("read.setReadOnly(true)","read.setTimeout(10)","source.bind(session.pool(),login(session))","finally{source.clear();}","routines.source(action.owner(),action.text(),2_000_000)");
        String controller=Files.readString(Path.of("src/main/java/com/dbcompanion/controller/SchedulerController.java"));
        assertThat(controller).contains("no-store").doesNotContain("@PostMapping","@PutMapping","@DeleteMapping");
    }
}
