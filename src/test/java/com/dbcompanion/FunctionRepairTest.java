package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.controller.FunctionRepairController;
import com.dbcompanion.model.FunctionCatalog.Failure;
import com.dbcompanion.service.FunctionRepairService;
import com.dbcompanion.service.FunctionRepairService.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class FunctionRepairTest {
    Source original=new Source("DEMO_FN",42,"FUNCTION DEMO_FN RETURN NUMBER IS BEGIN RETURN 1; END;","VALID",List.of());
    @Test void replacementIsBoundToAnExactStandaloneOwnerTarget(){
        assertThat(FunctionRepairService.sql("APP_TEST","DEMO_FN",original.source())).startsWith("CREATE OR REPLACE FUNCTION \"APP_TEST\".\"DEMO_FN\" RETURN NUMBER");
        assertThat(FunctionRepairService.sql("APP_TEST","DEMO_FN","FUNCTION \"DEMO_FN\"(p_input CLOB) RETURN CLOB IS BEGIN RETURN p_input; END;")).contains("\"APP_TEST\".\"DEMO_FN\" (");
        for(String source:List.of("FUNCTION OTHER RETURN NUMBER IS BEGIN RETURN 1; END;","FUNCTION DEMO_FN_EVIL RETURN NUMBER IS BEGIN RETURN 1; END;","FUNCTION SYS.DEMO_FN RETURN NUMBER IS BEGIN RETURN 1; END;","PROCEDURE DEMO_FN IS BEGIN NULL; END;","CREATE OR REPLACE "+original.source(),original.source()+"\n/","x".repeat(60001)))assertThatThrownBy(()->FunctionRepairService.sql("APP_TEST","DEMO_FN",source)).isInstanceOf(Failure.class);
    }
    @Test void concurrentReplacementAndExpiredConsentNeverOverwrite(){
        FunctionRepairService.unchanged(original,original);
        assertThatThrownBy(()->FunctionRepairService.unchanged(original,new Source("DEMO_FN",43,original.source(),"VALID",List.of()))).isInstanceOf(Failure.class);
        assertThatThrownBy(()->FunctionRepairService.unchanged(original,new Source("DEMO_FN",42,original.source()+" ","VALID",List.of()))).isInstanceOf(Failure.class);
        var plan=new Plan("t","APP_TEST",original,"SQL",Instant.now().plusSeconds(60));
        FunctionRepairService.require(plan,"t",true,"APP_TEST");
        assertThatThrownBy(()->FunctionRepairService.require(plan,"t",false,"APP_TEST")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->FunctionRepairService.require(plan,"t",true,"OTHER")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->FunctionRepairService.require(new Plan("t","APP_TEST",original,"SQL",Instant.EPOCH),"t",true,"APP_TEST")).isInstanceOf(Failure.class);
    }
    @Test void consumedPlanCannotRepeatAndOriginalSurvivesMultipleEdits(){
        Source[] current={original};int[] writes={0};
        var service=new FunctionRepairService(){
            @Override public Source read(PoolSession s,String name){return current[0];}
            @Override public Plan preview(PoolSession s,Source before,String edited){return new Plan("t","APP_TEST",before,edited,Instant.now().plusSeconds(60));}
            @Override public Source apply(PoolSession s,Plan plan){writes[0]++;return current[0]=new Source("DEMO_FN",42,plan.sql(),"VALID",List.of());}
        };
        var controller=new FunctionRepairController(service);var request=new MockHttpServletRequest();
        try(var session=new AccountSetupTest().session("APP_TEST")){
            request.getSession().setAttribute(PoolSession.ATTRIBUTE,session);controller.source("DEMO_FN",request);
            controller.preview(new FunctionRepairController.Edit("changed",false),request);
            assertThat(controller.apply(new FunctionRepairController.Apply("t",true),request).getStatusCode().value()).isEqualTo(200);
            assertThat(controller.apply(new FunctionRepairController.Apply("t",true),request).getStatusCode().value()).isEqualTo(409);
            var restore=(Plan)controller.preview(new FunctionRepairController.Edit(null,true),request).getBody();
            assertThat(restore.sql()).isEqualTo(original.source());
            var result=(Map<?,?>)controller.apply(new FunctionRepairController.Apply("t",true),request).getBody();
            assertThat(result.get("current")).isEqualTo(original);assertThat(writes[0]).isEqualTo(2);
        }
    }
}
