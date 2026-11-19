package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.DdsIdentity.*;
import com.dbcompanion.repository.DdsManagementRepository;
import com.dbcompanion.service.DdsManagementService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class DdsIdentityTest {
    static Map<String,String> user(String name,String auth){return Map.of("USERNAME",name,"ACCOUNT_STATUS","OPEN","AUTHENTICATION_TYPE",auth);}
    static Map<String,String> assignment(String type){return Map.of("DATA_ROLE","R","GRANTEE","PERSON","GRANTEE_TYPE",type);}
    static State state(boolean exists,boolean assigned){return new State(exists?List.of(user("PERSON","PASSWORD")):List.of(),
            List.of(Map.of("DATA_ROLE","R","MAPPED_TO",""),Map.of("DATA_ROLE","IAM","MAPPED_TO","IAM_OAUTH_GROUP=GROUP")),
            assigned?List.of(assignment("END USER")):List.of(),Set.of("CREATE END USER","GRANT ANY DATA ROLE"));}
    static Draft create(){return new Draft("CREATE","PERSON","",true);}
    @Test void previewNeverContainsPasswordAndExecutionValidatesWithoutSqlInjection(){
        assertThat(DdsIdentity.sql(create(),state(false,false))).contains("<password supplied at apply>");
        assertThat(DdsIdentity.executable(create(),state(false,false),"Synthetic_Only9!")).isEqualTo("CREATE END USER \"PERSON\" IDENTIFIED BY \"Synthetic_Only9!\"");
        for(String bad:List.of("short","has space12345","bad\"quote12345","bad\nnewline12345"))
            assertThatThrownBy(()->DdsIdentity.executable(create(),state(false,false),bad)).hasMessage("passwordInvalid");
        assertThat(new Apply("t","confirm",true,"Synthetic_Only9!").toString()).doesNotContain("Synthetic_Only9!");
        assertThatThrownBy(()->DdsIdentity.sql(new Draft("CREATE","A\";DROP", "",false),state(false,false))).hasMessage("invalid");
    }
    @Test void localRolesOnlyAndAssignmentTypeAreEnforced(){
        assertThat(DdsIdentity.sql(new Draft("GRANT","PERSON","R",false),state(true,false))).isEqualTo("GRANT DATA ROLE \"R\" TO \"PERSON\"");
        assertThat(DdsIdentity.sql(new Draft("REVOKE","PERSON","R",false),state(true,true))).isEqualTo("REVOKE DATA ROLE \"R\" FROM \"PERSON\"");
        assertThatThrownBy(()->DdsIdentity.sql(new Draft("GRANT","PERSON","IAM",false),state(true,false))).hasMessage("localAssignment");
        var s=state(true,false);var roleAssignment=new State(s.users(),s.roles(),List.of(assignment("DATA ROLE")),s.privileges());
        assertThatThrownBy(()->DdsIdentity.sql(new Draft("REVOKE","PERSON","R",false),roleAssignment)).hasMessage("missing");
    }
    @Test void permissionsDuplicatesAndReadbackFailClosed(){
        assertThatThrownBy(()->DdsIdentity.sql(create(),state(true,false))).hasMessage("exists");
        var s=state(false,false);
        assertThatThrownBy(()->DdsIdentity.sql(create(),new State(s.users(),s.roles(),s.assignments(),Set.of()))).hasMessage("endUserPrivilege");
        assertThatThrownBy(()->DdsIdentity.verify(create(),state(false,false))).hasMessage("verifyFailed");
        DdsIdentity.verify(create(),state(true,false));
        assertThatThrownBy(()->DdsIdentity.verify(new Draft("CREATE","PERSON","",false),state(true,false))).hasMessage("verifyFailed");
        assertThatThrownBy(()->DdsIdentity.verify(new Draft("REVOKE","PERSON","R",false),state(true,true))).hasMessage("verifyFailed");
    }
    static final class Fixture implements AutoCloseable {
        State current=state(false,false);int writes;boolean fail,wrongReadback;
        final SessionDataSource source=new SessionDataSource();
        final DdsManagementRepository repository=new DdsManagementRepository(new JdbcTemplate(source)){
            @Override public State identities(){return current;}
            @Override public void execute(String sql){writes++;if(fail)throw new IllegalStateException("uncertain");if(!wrongReadback)current=DdsIdentityTest.state(true,false);}
        };
        final DdsManagementService service=new DdsManagementService(source,repository);
        final PoolSession session=new PoolSession(new HikariDataSource(),"SYNTHETIC",()->{});
        Fixture(){session.initialize(new DatabaseSession(new DatabaseInfo("ADMIN","ADMIN","LOW","DB"),List.of("ADMIN","OTHER")));}
        Preview preview(){return service.identityPreview(session,new Request(create(),OrdsManagement.digest(current)));}
        Apply input(Preview p){return new Apply(p.token(),p.confirmation(),true,"Synthetic_Only9!");}
        @Override public void close(){session.close();}
    }
    @Test void applyRequiresConsentCurrentRevisionAndOwnSession(){try(var f=new Fixture();var other=new Fixture()){
        var p=f.preview();assertThatThrownBy(()->other.service.identityApply(other.session,f.input(p))).hasMessage("expired");
        assertThatThrownBy(()->f.service.identityApply(f.session,new Apply(p.token(),p.confirmation(),false,"Synthetic_Only9!"))).hasMessage("confirm");
        assertThat(f.writes).isZero();var old=f.preview();f.current=state(true,false);
        assertThatThrownBy(()->f.service.identityApply(f.session,f.input(old))).hasMessage("stale");assertThat(f.writes).isZero();
    }}
    @Test void singleUseAndReadbackRemainRequiredEvenAfterDdlFailure(){try(var f=new Fixture()){
        var p=f.preview();assertThat(f.service.identityApply(f.session,f.input(p))).containsEntry("status","VERIFIED");
        assertThatThrownBy(()->f.service.identityApply(f.session,f.input(p))).hasMessage("expired");assertThat(f.writes).isEqualTo(1);
    }
        for(boolean fail:List.of(false,true))try(var f=new Fixture()){
            f.fail=fail;f.wrongReadback=true;var p=f.preview();
            assertThatThrownBy(()->f.service.identityApply(f.session,f.input(p))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->f.service.identityApply(f.session,f.input(p))).hasMessage("expired");assertThat(f.writes).isEqualTo(1);
        }
    }
    @Test void schemaChangeAndNonAdminCannotManageUsers(){try(var f=new Fixture()){
        var p=f.preview();f.session.metadata().selectSchema("OTHER");
        assertThatThrownBy(()->f.service.identityApply(f.session,f.input(p))).hasMessage("ownSchema");assertThat(f.writes).isZero();
    }
        try(var s=new PoolSession(new HikariDataSource(),"SYNTHETIC",()->{});var f=new Fixture()){
            s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP")));
            assertThatThrownBy(()->f.service.identities(s)).hasMessage("adminRole");
        }
    }
}
