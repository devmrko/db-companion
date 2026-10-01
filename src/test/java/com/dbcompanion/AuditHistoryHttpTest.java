package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class AuditHistoryHttpTest {
    @LocalServerPort int port;
    @Test void auditReadAndArchiveActionsRequireLoginAndCsrf()throws Exception{
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:ListPaths.READ){
            var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/db/audit"+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(302);assertThat(r.body()).doesNotContain("RLS_INFO");
        }
        for(String path:new String[]{"/preview","/apply"}){
            var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/db/audit"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(403);
        }
    }
    private static final class ListPaths {static final String[] READ={"","/status","/help","/list?from=2026-01-01&to=2026-01-02","/detail?key=unknown"};}
}
