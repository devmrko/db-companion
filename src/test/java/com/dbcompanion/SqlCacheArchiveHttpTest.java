package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class SqlCacheArchiveHttpTest {
    @LocalServerPort int port;
    @Test void authenticationAndCsrfProtectArchiveAndMutations() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:new String[]{"","/status","/collector","/detail?key=0","/list?from=2026-01-01&to=2026-01-02","/access/users","/access?username=DEMO_APP"}){
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ai-executions/sql/archive"+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);assertThat(response.body()).doesNotContain("SQL_FULLTEXT");
        }
        for(String path:new String[]{"/preview","/apply","/access/preview","/access/apply"}){
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ai-executions/sql/archive"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
        }
    }
}
