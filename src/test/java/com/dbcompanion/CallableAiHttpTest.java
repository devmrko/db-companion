package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class CallableAiHttpTest {
    @LocalServerPort int port;
    @Test void authenticationAndCsrfProtectSourceInstallAndAiRequests()throws Exception{
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path: new String[]{"","/status","/script"})assertThat(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/db/functions/ai-query"+path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(302);
        for(String path: new String[]{"/install-preview","/install","/preview","/run"})assertThat(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/db/functions/ai-query"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
}
