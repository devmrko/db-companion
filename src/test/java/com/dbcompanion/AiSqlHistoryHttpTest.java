package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

/** Real unauthenticated HTTP boundary; no database/login mocking. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class AiSqlHistoryHttpTest {
    @LocalServerPort int port;
    @Test void allSupplementalReadsRequireLoginAndHaveNoWriteEndpoint() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for (String path: new String[]{"/ai-executions/sql/sources?source=cache&load=true&match=generate", "/ai-executions/sql/sources?source=awr&load=true&awrAllowed=true&match=all", "/ai-executions/sql/sources?source=audit&policies=true&match=select_ai", "/ai-executions/sql/sources/detail?id=0"}) {
            var uri=URI.create("http://127.0.0.1:"+port+path);
            var response=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("location").orElse("")).endsWith("/login");
            assertThat(response.body()).doesNotContain("SQL_FULLTEXT", "DBUSERNAME");
        }
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ai-executions/sql/sources")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
    }
}
