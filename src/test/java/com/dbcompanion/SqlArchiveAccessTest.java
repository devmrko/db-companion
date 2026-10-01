package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.controller.SqlArchiveAccessController;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.SqlArchiveAccessRepository;
import com.dbcompanion.repository.SqlArchiveAccessRepository.Access;
import com.dbcompanion.service.SqlArchiveAccessService;
import com.dbcompanion.service.SqlArchiveAccessService.Preview;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.*;

class SqlArchiveAccessTest {
    final SessionDataSource source=new SessionDataSource();
    Access current=new Access("DEMO_APP",42,false,false,true,false);
    int inspections,writes;
    boolean fail;
    final SqlArchiveAccessRepository repository=new SqlArchiveAccessRepository(new JdbcTemplate(source)){
        @Override public List<String> users(){return List.of("DEMO_APP");}
        @Override public Access inspect(String username){inspections++;if(!username.equals(current.username()))throw new IllegalArgumentException("Unknown user");return current;}
        @Override public Access apply(Access approved){
            if(!approved.equals(current))throw new IllegalArgumentException("Changed target");
            writes++;if(fail)throw new IllegalStateException("Unknown grant outcome");
            return current=new Access(current.username(),current.userId(),true,false,true,true);
        }
    };
    final SqlArchiveAccessService service=new SqlArchiveAccessService(source,repository);
    PoolSession session(String username,String database){
        var s=new PoolSession(new HikariDataSource(),"LOW",()->{});
        s.initialize(new DatabaseSession(new DatabaseInfo(username,username,"LOW",database),List.of(username,"OTHER")));
        return s;
    }
    @Test void onlyMissingFixedPrivilegesWithoutDelegation(){
        assertThat(SqlArchiveAccessRepository.plan(current)).containsExactly("GRANT READ ON SYS.V_$SQL TO \"DEMO_APP\"","GRANT CREATE JOB TO \"DEMO_APP\"");
        var full=SqlArchiveAccessRepository.plan(new Access("DEMO_APP",42,false,false,false,false));
        assertThat(full).hasSize(3);assertThat(String.join("\n",full)).doesNotContain("ANY","WITH GRANT OPTION","ADMIN OPTION","QUOTA","REVOKE","V_$MAPPED_SQL");
        assertThat(SqlArchiveAccessRepository.plan(new Access("DEMO_APP",42,false,true,true,true))).isEmpty();
    }
    @Test void usernamesAreQuotedAsOneIdentifier(){
        assertThat(SqlArchiveAccessRepository.quote("MiXeD\"Name")).isEqualTo("\"MiXeD\"\"Name\"");
        assertThat(SqlArchiveAccessRepository.plan(new Access("X\"; GRANT DBA TO Y--",1,false,false,true,true)))
                .containsExactly("GRANT READ ON SYS.V_$SQL TO \"X\"\"; GRANT DBA TO Y--\"");
        for(String s:List.of(""," ","A\nB","x".repeat(129)))assertThatThrownBy(()->SqlArchiveAccessRepository.quote(s)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void ordinaryLoginCannotEscalateBySelectingAdminSchema(){try(var s=session("DEMO_APP","DB")){
        assertThatThrownBy(()->service.users(s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.preview(s,"DEMO_APP")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.apply(s,null)).isInstanceOf(SecurityException.class);
        assertThat(inspections).isZero();assertThat(writes).isZero();
    }}
    @Test void previewHasNoWritesAndBindsDatabaseTargetAndExpiry(){try(var s=session("ADMIN","DB")){
        s.metadata().selectSchema("OTHER");
        var p=service.preview(s,"DEMO_APP");assertThat(writes).isZero();
        assertThat(p.database()).isEqualTo("DB");assertThat(p.before().username()).isEqualTo("DEMO_APP");
        assertThatThrownBy(()->service.apply(s,new Preview(p.token(),"OTHER_DB",p.before(),p.statements(),p.expires()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.apply(s,new Preview(p.token(),"DB",p.before(),p.statements(),Instant.EPOCH))).isInstanceOf(IllegalArgumentException.class);
        current=new Access("DEMO_APP",43,false,false,true,false);
        assertThatThrownBy(()->service.apply(s,p)).isInstanceOf(IllegalArgumentException.class);assertThat(writes).isZero();
    }}
    @Test void controllerRejectsNoConsentReplayAndNonAdmin(){try(var s=session("ADMIN","DB")){
        var r=request(s);var c=new SqlArchiveAccessController(service);
        var p=(Preview)c.preview(new SqlArchiveAccessController.Prepare("DEMO_APP"),r).getBody();
        assertThat(c.apply(new SqlArchiveAccessController.Apply(p.token(),false),r).getStatusCode().value()).isEqualTo(400);
        assertThat(c.apply(new SqlArchiveAccessController.Apply("wrong",true),r).getStatusCode().value()).isEqualTo(400);assertThat(writes).isZero();
        assertThat(c.apply(new SqlArchiveAccessController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(200);
        assertThat(c.apply(new SqlArchiveAccessController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(400);assertThat(writes).isEqualTo(1);
        assertThat(c.preview(new SqlArchiveAccessController.Prepare("DEMO_APP"),r).getStatusCode().value()).isEqualTo(400);
        try(var user=session("DEMO_APP","DB")){assertThat(c.users(request(user)).getStatusCode().value()).isEqualTo(403);}
        assertThat(c.users(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
    }}
    @Test void failedAttemptConsumesPreviewAndCannotBeRetried(){try(var s=session("ADMIN","DB")){
        fail=true;var r=request(s);var c=new SqlArchiveAccessController(service);
        var p=(Preview)c.preview(new SqlArchiveAccessController.Prepare("DEMO_APP"),r).getBody();
        assertThat(c.apply(new SqlArchiveAccessController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(503);
        assertThat(c.apply(new SqlArchiveAccessController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(400);assertThat(writes).isEqualTo(1);
    }}
    private MockHttpServletRequest request(PoolSession pool){var r=new MockHttpServletRequest();var h=new MockHttpSession();h.setAttribute(PoolSession.ATTRIBUTE,pool);r.setSession(h);return r;}
}
