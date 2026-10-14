package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class ProblemEvaluationHttpTest {
    @LocalServerPort int port;
    @Test void evaluationRequiresAuthenticationAndCsrf() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:new String[]{"preview","review","save"}){
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ai-test/problems/evaluation/"+path))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"consent\":true}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).doesNotContain("savedAttempt","criteriaHash");
        }
        var history=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ai-test/problems/evaluation/history?parentId="+ProblemEvaluationTest.P)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(history.statusCode()).isEqualTo(302);assertThat(history.headers().firstValue("location").orElse("")).endsWith("/login");
    }
}
