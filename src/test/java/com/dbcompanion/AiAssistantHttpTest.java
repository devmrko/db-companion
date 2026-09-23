package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class AiAssistantHttpTest {
    @LocalServerPort int port;
    @Test void realHttpRequiresLoginAndCsrfForEveryAssistantAction() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:List.of("/ai-assistant","/ai-assistant/options","/ai-assistant/selection","/db/functions/explain/preview","/db/functions/explain",
                "/ai-test","/ai-test/options","/ai-test/profile","/ai-test/selection","/ai-test/preview","/ai-test/generate","/ai-test/cancel","/ai-test/execute/preview","/ai-test/execute","/ai-test/review/preview","/ai-test/review",
                "/ai-test/evidence/options","/ai-test/evidence/search","/ai-test/evidence/choose")){
            var uri=URI.create("http://127.0.0.1:"+port+path);
            var get=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(get.statusCode()).isEqualTo(302);
            assertThat(get.headers().firstValue("location").orElse("")).endsWith("/login");
            var post=client.send(HttpRequest.newBuilder(uri).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}" )).build(),HttpResponse.BodyHandlers.ofString());assertThat(post.statusCode()).isEqualTo(403);
        }
    }
}
