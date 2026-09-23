package com.dbcompanion;

import com.dbcompanion.controller.CatalogColumnProbeController;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths=",
    "spring.profiles.active=catalog-diagnostics","app.catalog-probe.login=APP","app.catalog-probe.catalog=CAT",
    "app.catalog-probe.schema=S","app.catalog-probe.table=T","app.catalog-probe.link=L"})
class CatalogColumnProbeHttpTest {
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Test void temporaryProbeRequiresRealLoginAndCsrf() throws Exception {
        assertThat(context.getBeansOfType(CatalogColumnProbeController.class)).hasSize(1);
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var uri=URI.create("http://127.0.0.1:"+port+"/db/external-sources/catalog-column-probe");
        var get=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(302);assertThat(get.headers().firstValue("location").orElse("")).endsWith("/login");
        var post=client.send(HttpRequest.newBuilder(uri).header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("step=PARENT_TYPE")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(403);
    }
}
