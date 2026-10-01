package com.dbcompanion;

import com.dbcompanion.controller.OntologyPipelineController.Step;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class OntologyPipelineContractTest {
    @LocalServerPort int port;
    @Autowired JsonMapper json;

    @Test void individualCallDoesNotRequireTheBatchAuthorizationOption(){
        var step=json.readValue("{\"token\":\"plan\",\"index\":0,\"consent\":true}",Step.class);
        assertThat(step.token()).isEqualTo("plan");assertThat(step.index()).isZero();assertThat(step.consent()).isTrue();
        assertThat(Boolean.TRUE.equals(step.all())).isFalse();
    }
    @Test void onlyExplicitTrueAuthorizesAllRemainingCalls(){
        for(String value:new String[]{"true","false","null"}){
            var step=json.readValue("{\"token\":\"plan\",\"index\":0,\"consent\":true,\"all\":"+value+"}",Step.class);
            assertThat(Boolean.TRUE.equals(step.all())).isEqualTo(value.equals("true"));
        }
    }
    @Test void consentAndCallIndexRemainRequired(){
        for(String body:new String[]{"{\"token\":\"plan\",\"index\":0}","{\"token\":\"plan\",\"index\":0,\"consent\":null}","{\"token\":\"plan\",\"consent\":true}"}){
            assertThatThrownBy(()->json.readValue(body,Step.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
        }
    }
}
