package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

/** Real application HTTP security, without a fabricated DB session. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class ExternalSourcesHttpTest {
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
    @Test void catalogDiagnosticsAreAbsentWithoutExplicitProfile(){
        assertThat(context.getBeansOfType(com.dbcompanion.controller.CatalogColumnProbeController.class)).isEmpty();
    }
    @Test void catalogOperationsRequireLoginAndCsrf() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:List.of("preview","mount","browse?name=X&level=schemas")){
            var uri=URI.create("http://127.0.0.1:"+port+"/db/external-sources/catalogs/"+path);
            assertThat(client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(302);
            assertThat(client.send(HttpRequest.newBuilder(uri).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}" )).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
    }
    @Test void readEndpointsRequireLoginAndPostIsBlocked() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:List.of("/db/external-sources","/db/external-sources/list?schema=APP&kind=links","/db/external-sources/detail?schema=APP&kind=tables&owner=APP&name=T","/db/external-sources/acl","/db/external-sources/acl/schema?schema=APP","/db/external-sources/catalogs","/db/external-sources/catalogs/detail?name=T")){
            var uri=URI.create("http://127.0.0.1:"+port+path);
            var result=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).isEqualTo(302);assertThat(result.headers().firstValue("location").orElse("")).endsWith("/login");
            assertThat(client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
    }
}
