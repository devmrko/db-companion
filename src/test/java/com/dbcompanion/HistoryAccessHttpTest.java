package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class HistoryAccessHttpTest {
    @LocalServerPort int port;
    @Test void loginAndCsrfProtectScopeAndGrants() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var uri=URI.create("http://127.0.0.1:"+port+"/tables/history/access?schema=APP&table=T");
        assertThat(client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(302);
        assertThat(client.send(HttpRequest.newBuilder(uri).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
}
