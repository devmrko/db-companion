package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.controller.AiNetworkSetupController;
import com.dbcompanion.model.*;
import com.dbcompanion.service.AiNetworkSetupService;
import com.dbcompanion.service.AiNetworkSetupService.*;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class AiNetworkSetupTest {
    PoolSession session(String name){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo(name,name,"LOW","DB"),List.of(name)));return s;}
    @Test void fixedExactHostsAndPrincipalsRejectInjectionAndBroadAccess(){
        assertThat(AiNetworkSetupService.sql(new Input("ADMIN","OPENAI"))).contains("'api.openai.com'","XS$NAME_LIST('http')","principal_name => 'ADMIN'").doesNotContain("connect","*","REMOVE","CREATE_ACL");
        for(String name:List.of("SYS","SYSTEM","PUBLIC","A'B","A.B","A;X"))assertThatThrownBy(()->AiNetworkSetupService.sql(new Input(name,"OPENAI"))).isInstanceOf(AiAssistant.Failure.class);
        for(String provider:List.of("*","api.openai.com","OPENAI'","OCI","http://localhost"))assertThatThrownBy(()->AiNetworkSetupService.sql(new Input("APP_TEST",provider))).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void deniesTimedInvertedAndPortRestrictedEntriesDoNotCountAsTheRequestedGrant(){
        assertThat(AiNetworkSetupService.directGrant(List.of(new Ace(null,null,1,"GRANT","NO","DB","http",null,null)))).isTrue();
        for(var ace:List.of(new Ace(null,null,1,"DENY","NO","DB","http",null,null),new Ace(null,null,1,"GRANT","YES","DB","http",null,null),new Ace(443,443,1,"GRANT","NO","DB","http",null,null),new Ace(null,null,1,"GRANT","NO","DB","connect",null,null),new Ace(null,null,1,"GRANT","NO","DB","http",null,"2020-01-01")))assertThat(AiNetworkSetupService.directGrant(List.of(ace))).isFalse();
    }
    @Test void nonAdminAndOtherSelectedSchemaFailBeforeConnecting(){
        try(var s=session("APP_TEST");var admin=session("ADMIN")){
            assertThatThrownBy(()->new AiNetworkSetupService().status(s,new Input("ADMIN","OPENAI"))).isInstanceOf(AiAssistant.Failure.class);
            admin.metadata().refreshSchemas(List.of("ADMIN","APP_TEST"));admin.metadata().selectSchema("APP_TEST");
            assertThatThrownBy(()->new AiNetworkSetupService().status(admin,new Input("ADMIN","OPENAI"))).isInstanceOf(AiAssistant.Failure.class);
        }
    }
    @Test void previewConsentSessionExpiryAndOneShotApplyAreEnforced(){
        int[] writes={0};boolean[] fail={false};Instant[] expires={Instant.now().plusSeconds(60)};
        var service=new AiNetworkSetupService(){
            @Override public Plan preview(PoolSession s,Input in){return new Plan("token",in,new State(in.name(),"api.openai.com",1,List.of(),false),sql(in),expires[0]);}
            @Override public Result apply(PoolSession s,Plan p){writes[0]++;if(fail[0])throw new IllegalStateException("uncertain");return new Result("VERIFIED",p.before());}
        };
        var c=new AiNetworkSetupController(service);var r=new MockHttpServletRequest();var input=new Input("ADMIN","OPENAI");var apply=new AiNetworkSetupController.Apply("token",true);
        try(var s=session("ADMIN");var other=session("ADMIN");var user=session("APP_TEST")){
            r.getSession().setAttribute(PoolSession.ATTRIBUTE,user);assertThat(c.preview(input,r).getStatusCode().value()).isEqualTo(403);
            r.getSession().setAttribute(PoolSession.ATTRIBUTE,s);c.preview(input,r);
            assertThat(c.apply(new AiNetworkSetupController.Apply("token",false),r).getStatusCode().value()).isEqualTo(409);
            assertThat(c.apply(apply,r).getStatusCode().value()).isEqualTo(200);
            assertThat(c.apply(apply,r).getStatusCode().value()).isEqualTo(409);assertThat(writes[0]).isEqualTo(1);
            c.preview(input,r);fail[0]=true;assertThat(c.apply(apply,r).getStatusCode().value()).isEqualTo(503);assertThat(c.apply(apply,r).getStatusCode().value()).isEqualTo(409);assertThat(writes[0]).isEqualTo(2);
            expires[0]=Instant.EPOCH;c.preview(input,r);assertThat(c.apply(apply,r).getStatusCode().value()).isEqualTo(409);assertThat(writes[0]).isEqualTo(2);
            expires[0]=Instant.now().plusSeconds(60);c.preview(input,r);r.getSession().setAttribute(PoolSession.ATTRIBUTE,other);assertThat(c.apply(apply,r).getStatusCode().value()).isEqualTo(409);assertThat(writes[0]).isEqualTo(2);
        }
    }
    @Test void aclErrorExplainsAdminRemedyWithoutClaimingAuthenticationFailed(){
        var s=AiErrorExplanation.explain("AI request",new java.sql.SQLException("ORA-24247: Network access denied"));
        assertThat(s.display()).contains("/db/accounts#ai-network").doesNotContain("invalid API key");
    }
}
