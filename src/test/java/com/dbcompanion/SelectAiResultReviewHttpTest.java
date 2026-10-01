package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class SelectAiResultReviewHttpTest {
    @LocalServerPort int port;
    @Test void noUnauthenticatedOrCsrfFreeReviewRequests() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:new String[]{"/ai-test/result-review/preview","/ai-test/result-review"}){
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"consent\":true}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).doesNotContain("executedSql","appliedGlossary");
        }
    }
}
