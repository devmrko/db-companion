package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.config.SelectAiExecutionSettings;
import com.dbcompanion.model.*;
import com.dbcompanion.model.CallableAi.*;
import com.dbcompanion.repository.CallableAiRepository;
import com.dbcompanion.service.CallableAiService;
import com.dbcompanion.controller.CallableAiController;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class CallableAiStateTest {
    String current="MISSING",fingerprint="a";int installs,runs,grants;boolean granted;
    final SessionDataSource source=new SessionDataSource();final JsonMapper json=new JsonMapper();
    final CallableAiRepository repository=new CallableAiRepository(new JdbcTemplate(source),json,new SelectAiExecutionSettings(300)){
        @Override public String state(String owner){return current;}
        @Override public String fingerprint(){return fingerprint;}
        @Override public Status status(String owner){return new Status(owner,current,List.of(),List.of("P"),List.of("T"));}
        @Override public void install(String owner){assertThat(owner).isEqualTo("APP");installs++;current="READY";}
        @Override public JsonNode run(String owner,Request request){assertThat(owner).isEqualTo("APP");runs++;return json.readTree("{\"mode\":\""+request.mode()+"\"}");}
        @Override public List<String> users(){return List.of("APP");}
        @Override public Access access(String username){return new Access(username,granted);}
        @Override public Access grant(String username){assertThat(username).isEqualTo("APP");grants++;granted=true;return access(username);}
    };
    final CallableAiService service=new CallableAiService(source,repository);
    PoolSession session(String owner){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo(owner,owner,"LOW","DB"),List.of(owner,"OTHER")));s.metadata().selectSchema("OTHER");return s;}
    MockHttpServletRequest request(PoolSession s){var r=new MockHttpServletRequest();var h=new MockHttpSession();r.setSession(h);h.setAttribute(PoolSession.ATTRIBUTE,s);return r;}
    @Test void installRequiresCurrentSourceLoginOwnerAndConsent(){try(var s=session("APP")){
        var c=new CallableAiController(service);var r=request(s);var p=(Install)c.installPreview(r).getBody();assertThat(p.owner()).isEqualTo("APP");
        assertThat(c.install(new CallableAiController.Apply(p.token(),false),r).getStatusCode().value()).isEqualTo(400);
        assertThat(c.install(new CallableAiController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(200);
        assertThat(c.install(new CallableAiController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(400);assertThat(installs).isEqualTo(1);
    }}
    @Test void changedSourceOrExpiredPreviewCannotInstall(){try(var s=session("APP")){
        var p=service.preview(s);fingerprint="b";assertThatThrownBy(()->service.install(s,p)).isInstanceOf(IllegalArgumentException.class);
        fingerprint="a";assertThatThrownBy(()->service.install(s,new Install(p.token(),p.owner(),p.fingerprint(),p.sql(),Instant.EPOCH))).isInstanceOf(IllegalArgumentException.class);assertThat(installs).isZero();
    }}
    @Test void paidModesRequireConsentAndConsumedCallsNeverRetry(){try(var s=session("APP")){
        current="READY";var c=new CallableAiController(service);var r=request(s);var p=(Run)c.preview(new Request("q","P",true,false,List.of(),"SQL",200),r).getBody();
        assertThat(c.run(new CallableAiController.Apply(p.token(),false),r).getStatusCode().value()).isEqualTo(400);assertThat(runs).isZero();
        assertThat(c.run(new CallableAiController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(200);
        assertThat(c.run(new CallableAiController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(400);assertThat(runs).isEqualTo(1);
    }}
    @Test void contextOnlyDoesNotRequirePaidConsent(){try(var s=session("APP")){
        current="READY";var c=new CallableAiController(service);var r=request(s);var p=(Run)c.preview(new Request("q","P",false,true,List.of("T"),"CONTEXT",200),r).getBody();
        assertThat(c.run(new CallableAiController.Apply(p.token(),false),r).getStatusCode().value()).isEqualTo(200);assertThat(runs).isEqualTo(1);
    }}
    @Test void onlyAdminCanGrantAndOnlyOnceAfterConfirmation(){try(var user=session("APP");var admin=session("ADMIN")){
        assertThatThrownBy(()->service.users(user)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.grantPreview(user,"APP")).isInstanceOf(IllegalArgumentException.class);
        var c=new CallableAiController(service);var r=request(admin);var p=(Grant)c.grantPreview(new CallableAiController.User("APP"),r).getBody();
        assertThat(p.sql()).isEqualTo("GRANT CREATE PROCEDURE TO \"APP\"").doesNotContain("ANY","ADMIN OPTION");
        assertThat(c.grant(new CallableAiController.Apply(p.token(),false),r).getStatusCode().value()).isEqualTo(400);
        assertThat(c.grant(new CallableAiController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(200);
        assertThat(c.grant(new CallableAiController.Apply(p.token(),true),r).getStatusCode().value()).isEqualTo(400);assertThat(grants).isEqualTo(1);
    }}
}
