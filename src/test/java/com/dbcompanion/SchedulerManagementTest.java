package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.SchedulerManagement.*;
import com.dbcompanion.repository.SchedulerManagementRepository;
import com.dbcompanion.service.SchedulerManagementService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class SchedulerManagementTest {
    static Map<String,String> job(){var r=new TreeMap<String,String>();r.putAll(Map.of("JOB_NAME","TEST_JOB","JOB_TYPE","PLSQL_BLOCK","JOB_STYLE","REGULAR","AUTO_DROP","FALSE","SYSTEM","FALSE","NUMBER_OF_ARGUMENTS","0","JOB_CLASS","DEFAULT_JOB_CLASS","ENABLED","FALSE","STATE","DISABLED"));r.put("JOB_ACTION","BEGIN NULL; END;");r.put("REPEAT_INTERVAL","FREQ=MINUTELY;INTERVAL=60");r.put("COMMENTS","original");return r;}
    static State state(Map<String,String> row){return new State(row==null?List.of():List.of(row),Set.of("CREATE JOB"));}
    static Draft draft(String action){return new Draft("APP",action,"TEST_JOB","BEGIN NULL; END;",60,"original");}
    @Test void createsDisabledAndQuotesWithoutInjectingOuterDdl(){
        var d=new Draft("APP","CREATE","TEST_JOB","BEGIN DBMS_OUTPUT.PUT_LINE('한국어 🧪'); END;",60,"quote ' value");
        assertThat(SchedulerManagement.sql(d,state(null),"APP")).contains("enabled => FALSE, auto_drop => FALSE","''한국어 🧪''","'quote '' value'");
        assertThatThrownBy(()->SchedulerManagement.sql(new Draft("APP","CREATE","A;X","BEGIN NULL; END;",1,""),state(null),"APP")).hasMessage("invalid");
        assertThatThrownBy(()->SchedulerManagement.sql(draft("CREATE"),state(null),"OTHER")).hasMessage("ownSchema");
        assertThatThrownBy(()->SchedulerManagement.sql(draft("CREATE"),new State(List.of(),Set.of()),"APP")).hasMessage("privilege");
        assertThatThrownBy(()->SchedulerManagement.sql(draft("CREATE"),state(job()),"APP")).hasMessage("exists");
    }
    @Test void unsupportedRunningAndEnabledJobsFailClosed(){
        for(var change:List.of(Map.of("JOB_TYPE","EXECUTABLE"),Map.of("AUTO_DROP","TRUE"),Map.of("SYSTEM","TRUE"),Map.of("PROGRAM_NAME","P"),Map.of("SCHEDULE_NAME","S"),Map.of("CREDENTIAL_NAME","C"),Map.of("DESTINATION","D"),Map.of("EVENT_CONDITION","X"),Map.of("NUMBER_OF_ARGUMENTS","1"))){var r=job();r.putAll(change);assertThatThrownBy(()->SchedulerManagement.sql(draft("UPDATE"),state(r),"APP")).hasMessage("unsupported");}
        var r=job();r.put("STATE","RUNNING");assertThatThrownBy(()->SchedulerManagement.sql(draft("RUN"),state(r),"APP")).hasMessage("running");
        r.put("STATE","SCHEDULED");r.put("ENABLED","TRUE");assertThatThrownBy(()->SchedulerManagement.sql(draft("UPDATE"),state(r),"APP")).hasMessage("disableFirst");
        assertThatThrownBy(()->SchedulerManagement.sql(draft("RUN"),state(r),"APP")).hasMessage("disableFirst");
    }
    @Test void previousSettingsCanBeRestoredAndMismatchesAreNotSuccess(){
        var r=job();var old=SchedulerManagement.previous("APP",r);
        var changed=new Draft("APP","UPDATE","TEST_JOB","BEGIN NULL; NULL; END;",120,"changed");
        assertThat(SchedulerManagement.sql(changed,state(r),"APP")).contains("'job_action'","'repeat_interval'","'comments'");
        assertThatThrownBy(()->SchedulerManagement.verify(changed,state(r))).hasMessage("verifyFailed");
        r.put("JOB_ACTION",changed.code());r.put("REPEAT_INTERVAL","FREQ=MINUTELY;INTERVAL=120");r.put("COMMENTS","changed");SchedulerManagement.verify(changed,state(r));
        assertThat(SchedulerManagement.sql(old,state(r),"APP")).contains("FREQ=MINUTELY;INTERVAL=60","'original'");
        assertThat(SchedulerManagement.sql(draft("RUN"),state(r),"APP")).contains("use_current_session => FALSE");
    }
    static final class Fixture implements AutoCloseable {
        State current=state(null);int writes;boolean fail;
        final SessionDataSource source=new SessionDataSource();
        final SchedulerManagementRepository repo=new SchedulerManagementRepository(new JdbcTemplate(source)){
            @Override public State state(){return current;}
            @Override public void execute(String sql){writes++;if(fail)throw new IllegalStateException("uncertain");current=SchedulerManagementTest.state(job());}
        };
        final SchedulerManagementService service=new SchedulerManagementService(source,repo);
        final PoolSession session=new PoolSession(new HikariDataSource(),"SYNTHETIC",()->{});
        Fixture(){session.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));}
        Preview preview(String action){return service.preview(session,new Request(draft(action),OrdsManagement.digest(current)));}
        Apply apply(Preview p){return new Apply(p.token(),p.confirmation(),true);}
        @Override public void close(){session.close();}
    }
    @Test void tokenSingleUseAndExactConsentBeforeWrite(){try(var f=new Fixture()){
        var p=f.preview("CREATE");assertThatThrownBy(()->f.service.apply(f.session,new Apply(p.token(),"wrong",true))).hasMessage("confirm");assertThat(f.writes).isZero();
        var consumed=f.preview("CREATE");assertThat(f.service.apply(f.session,f.apply(consumed))).containsEntry("status","VERIFIED");
        assertThatThrownBy(()->f.service.apply(f.session,f.apply(consumed))).hasMessage("expired");assertThat(f.writes).isEqualTo(1);
    }}
    @Test void staleCrossSessionAndUncertainRequestsNeverReplay(){try(var f=new Fixture();var other=new Fixture()){
        var p=f.preview("CREATE");assertThatThrownBy(()->other.service.apply(other.session,f.apply(p))).hasMessage("expired");f.current=state(job());
        assertThatThrownBy(()->f.service.apply(f.session,f.apply(p))).hasMessage("stale");assertThat(f.writes).isZero();
        var p2=f.preview("UPDATE");f.fail=true;assertThatThrownBy(()->f.service.apply(f.session,f.apply(p2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.service.apply(f.session,f.apply(p2))).hasMessage("expired");assertThat(f.writes).isEqualTo(1);
    }}
    @Test void runIsSubmittedNotCompletedAndPreviewShowsActualCode(){try(var f=new Fixture()){
        f.current=state(job());var p=f.preview("RUN");assertThat(p.executionCode()).isEqualTo("BEGIN NULL; END;");
        assertThat(f.service.apply(f.session,f.apply(p))).containsEntry("status","SUBMITTED");
    }}
}
