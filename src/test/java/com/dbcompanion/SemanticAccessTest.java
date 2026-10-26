package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.controller.SemanticContextController;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.DatabaseSession;
import com.dbcompanion.model.SemanticContext.Capability;
import com.dbcompanion.repository.SemanticContextRepository;
import com.dbcompanion.service.SemanticContextService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.*;

class SemanticAccessTest {
    boolean granted;
    int writes;
    final SessionDataSource source=new SessionDataSource();
    final SemanticContextRepository repository=new SemanticContextRepository(new JdbcTemplate(source)) {
        @Override public Capability capability(){return new Capability("23.26.4.1.0",true,"supported","API_OWNER");}
        @Override public List<String> accessUsers(){return List.of("APP");}
        @Override public boolean accessGranted(String owner,String user){if(!accessUsers().contains(user))throw new IllegalArgumentException();return granted;}
        @Override public void grantAccess(String owner,String user){if(accessGranted(owner,user))throw new IllegalArgumentException();writes++;granted=true;}
    };
    final SemanticContextService service=new SemanticContextService(source,repository);
    PoolSession session(String owner){
        var s=new PoolSession(new HikariDataSource(),"TEST",()->{});
        s.initialize(new DatabaseSession(new DatabaseInfo(owner,owner,"TEST","SYNTHETIC"),List.of(owner)));return s;
    }
    @Test void nonAdminCannotInspectOrGrantAndNoWriteOccurs(){try(var s=session("APP")){
        assertThatThrownBy(()->service.accessUsers(s)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.accessPreview(s,"APP")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.grantAccess(s,null)).isInstanceOf(RuntimeException.class);
        assertThat(writes).isZero();
    }}
    @Test void exactTargetConsentSingleUseAndExpirationAreEnforced(){try(var s=session("ADMIN")){
        var request=new MockHttpServletRequest();request.setSession(new MockHttpSession());request.getSession().setAttribute(PoolSession.ATTRIBUTE,s);
        var controller=new SemanticContextController(service);
        var preview=(SemanticContextService.Grant)controller.preview(new SemanticContextController.User("APP"),request).getBody();
        assertThat(preview.sql()).isEqualTo("GRANT EXECUTE ON \"API_OWNER\".\"DBMS_AI_SEM\" TO \"APP\"");
        assertThat(controller.grant(new SemanticContextController.Apply(preview.token(),false),request).getStatusCode().value()).isEqualTo(400);
        assertThatThrownBy(()->service.grantAccess(s,new SemanticContextService.Grant(preview.token(),"APP","API_OWNER",preview.sql(),Instant.EPOCH))).isInstanceOf(RuntimeException.class);
        assertThat(controller.grant(new SemanticContextController.Apply(preview.token(),true),request).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.grant(new SemanticContextController.Apply(preview.token(),true),request).getStatusCode().value()).isEqualTo(400);
        assertThatThrownBy(()->service.accessPreview(s,"APP")).isInstanceOf(RuntimeException.class);
        assertThat(writes).isEqualTo(1);
    }}
    @Test void normalizationOnlyAcceptsEquivalentUppercaseCallbackIdentifiers(){
        assertThat(SemanticContextRepository.attributeEquals("global_callback_func","APP.CONTEXT","\"APP\".CONTEXT")).isTrue();
        assertThat(SemanticContextRepository.attributeEquals("global_callback_func","APP.CONTEXT","\"App\".CONTEXT")).isFalse();
        assertThat(SemanticContextRepository.attributeEquals("scope","global","\"global\"")).isFalse();
    }
}
