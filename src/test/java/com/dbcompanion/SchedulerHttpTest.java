package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class SchedulerHttpTest {
    @LocalServerPort int port;
    @Test void actualApplicationRequiresLoginAndRejectsCsrfLessWrites() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:List.of("/db/scheduler","/db/scheduler/list?schema=APP","/db/scheduler/detail?schema=APP&name=ETL","/db/scheduler/code?schema=APP&name=ETL","/db/scheduler/runs?schema=APP&name=ETL","/db/scheduler/run?schema=APP&name=ETL&id=1")){
            var uri=URI.create("http://127.0.0.1:"+port+path);var response=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);assertThat(response.headers().firstValue("location").orElse("")).endsWith("/login");
            assertThat(client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
    }
}
