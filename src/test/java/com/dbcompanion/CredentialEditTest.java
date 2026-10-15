package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.controller.CredentialEditController;
import com.dbcompanion.model.CredentialCatalog.Failure;
import com.dbcompanion.service.CredentialEditService;
import com.dbcompanion.service.CredentialEditService.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class CredentialEditTest {
    Plan plan(){return new Plan("test-token","APP_TEST","DEMO_CRED","PASSWORD",false,new Identity(42,"hash","TRUE"),Instant.now().plusSeconds(300),"SQL without value");}
    @Test void consentOwnerExpiryAndTokenAreRequired(){
        var p=plan();CredentialEditService.require(p,p.token(),true,p.owner(),Instant.now());
        assertThatThrownBy(()->CredentialEditService.require(p,p.token(),false,p.owner(),Instant.now())).isInstanceOf(Failure.class);
        assertThatThrownBy(()->CredentialEditService.require(p,"other",true,p.owner(),Instant.now())).isInstanceOf(Failure.class);
        assertThatThrownBy(()->CredentialEditService.require(p,p.token(),true,"OTHER",Instant.now())).isInstanceOf(Failure.class);
        assertThatThrownBy(()->CredentialEditService.require(p,p.token(),true,p.owner(),p.expires())).isInstanceOf(Failure.class);
    }
    @Test void valuesAreBoundedWithoutRemovingQuotesUnicodeOrWhitespaceFromPasswords(){
        CredentialEditService.input("PASSWORD","synthetic ' 한국어 🌏 space");
        for(String v:new String[]{"","\0","가".repeat(1334)})assertThatThrownBy(()->CredentialEditService.input("PASSWORD",v)).isInstanceOf(Failure.class);
        for(String v:new String[]{"name space","name\"","name\n"})assertThatThrownBy(()->CredentialEditService.input("USERNAME",v)).isInstanceOf(Failure.class);
        assertThat(new CredentialEditController.Apply("token","synthetic-secret",true).toString()).doesNotContain("synthetic-secret");
    }
    @Test void previewContainsNoIdentityAndApplyIsOneShotEvenOnUncertainFailure(){
        var fixture=new OntologyReadCacheTest();int[] writes={0};
        var service=new CredentialEditService(fixture.source,fixture.jdbc,null){
            @Override public Plan preview(PoolSession s,String n,String a,boolean restore){return plan();}
            @Override public Map<String,Object> apply(PoolSession s,Plan p,String v){writes[0]++;throw new IllegalStateException("private synthetic-secret");}
        };
        var controller=new CredentialEditController(service);var r=new MockHttpServletRequest();
        try(var s=new AccountSetupTest().session("APP_TEST")){
            r.getSession().setAttribute(PoolSession.ATTRIBUTE,s);
            var preview=controller.preview(new CredentialEditController.Preview("DEMO_CRED","PASSWORD",false),r);
            assertThat(preview.getBody().toString()).doesNotContain("hash","identity","synthetic-secret");
            assertThat(controller.apply(new CredentialEditController.Apply("test-token","synthetic-secret",false),r).getStatusCode().value()).isEqualTo(400);
            var failed=controller.apply(new CredentialEditController.Apply("test-token","synthetic-secret",true),r);
            assertThat(failed.getStatusCode().value()).isEqualTo(503);assertThat(failed.getBody().toString()).doesNotContain("synthetic-secret");
            assertThat(controller.apply(new CredentialEditController.Apply("test-token","synthetic-secret",true),r).getStatusCode().value()).isEqualTo(409);
            assertThat(writes[0]).isEqualTo(1);
        }
    }
    @Test void replacingCredentialAfterPreviewPreventsWrite(){
        var fixture=new OntologyReadCacheTest();int[] writes={0};
        var service=new CredentialEditService(fixture.source,fixture.jdbc,null){
            @Override protected Identity identity(String o,String n){return new Identity(43,"hash","TRUE");}
            @Override protected void update(Plan p,String v){writes[0]++;}
        };
        try(var s=new AccountSetupTest().session("APP_TEST")){
            assertThatThrownBy(()->service.apply(s,plan(),"synthetic-secret")).isInstanceOf(Failure.class);
            assertThat(writes[0]).isZero();
        }
    }
}
