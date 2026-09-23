package com.dbcompanion;

import com.dbcompanion.controller.SelectAiInspectionController.*;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class SelectAiInspectionUiTest {
    @LocalServerPort int port;@Autowired JsonMapper json;
    @Test void inspectionEndpointsRequireCsrfAndAuthenticationAndModuleIsServed() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String suffix:List.of("","/table","/feedback","/feedback/detail")){
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ai-test/inspection"+suffix)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(suffix).isEqualTo(403);
        }
        var page=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ai-test/options")).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(page.statusCode()).isEqualTo(302);
        var script=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/js/select-ai-inspection.mjs")).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(script.statusCode()).isEqualTo(200);assertThat(script.body()).contains("renderPromptInspection","parseShowprompt");
    }
    @Test void requestsDoNotAcceptClientSqlOrCatalogEvidence(){
        assertThat(json.readValue("{\"profile\":\"P\",\"question\":\"샘플게임\"}",Load.class).question()).isEqualTo("샘플게임");
        assertThat(json.readValue("{\"id\":\"snapshot\",\"search\":\"질문\",\"page\":1}",Feedback.class).search()).isEqualTo("질문");
        assertThatThrownBy(()->json.readValue("{\"id\":\"snapshot\",\"owner\":\"APP\",\"name\":\"T\",\"sql\":\"SELECT secret\"}",Table.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
    }
}
