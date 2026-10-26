package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP, no fake login or mock database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class CredentialCatalogHttpTest {
    @LocalServerPort int port;
    @Test void creationRequiresCsrfAndAuthentication() throws Exception {
        var client=HttpClient.newHttpClient();
        for(String path:List.of("preview","apply")){
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/db/objects/create/"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
    }
    @Test void everyCatalogEndpointRequiresLoginAndNoWriteIsExposed() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:List.of("/db/credentials","/db/credentials/list?schema=APP","/db/credentials/detail?schema=APP&name=CRED")){
            var uri=URI.create("http://127.0.0.1:"+port+path);
            var get=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(get.statusCode()).isEqualTo(302);assertThat(get.headers().firstValue("location").orElse("")).endsWith("/login");
            assertThat(client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
    }
}
