package com.dbcompanion;

import com.dbcompanion.controller.ProfileAuditProbeController;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

/** Real embedded HTTP server; no mock DB, security principal or fake successful login. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.oracle.wallet-path=","app.oracle.wallet-paths=", "spring.profiles.active=profile-audit-diagnostics"})
class ProfileAuditProbeHttpTest {
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Test void customerDiagnosticClassesAreNotShippedEvenWithDiagnosticProfileEnabled() {
        for (String name : new String[]{"CredentialProbeController", "ExistingProfileProbeController",
                "FeedbackProbeController", "HistoryProbeController", "MappedSqlAccessSetupController"}) {
            assertThat(org.springframework.util.ClassUtils.isPresent("com.dbcompanion.controller." + name,
                    getClass().getClassLoader())).as(name).isFalse();
        }
    }
    @Test void explicitDiagnosticProfileDoesNotBypassAuthenticationOrCsrf() throws Exception {
        assertThat(context.getBeansOfType(ProfileAuditProbeController.class)).hasSize(1);
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var uri = URI.create("http://127.0.0.1:" + port + "/ai-profiles/audit-probe");
        var get = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(302);
        assertThat(get.headers().firstValue("location").orElse("")).endsWith("/login");
        var post = client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("nonce=x&action=run")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(403);
        var instructions = client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("nonce=x&action=run-instructions")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(instructions.statusCode()).isEqualTo(403);
        for (String action : new String[]{"run-history", "refresh-history"}) {
            var response = client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("nonce=x&action=" + action)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
        }
    }
}
