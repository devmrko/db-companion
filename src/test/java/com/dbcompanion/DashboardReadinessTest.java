package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.controller.AiSetupController;
import com.dbcompanion.model.AiProfile;
import com.dbcompanion.service.AiSetupService;
import com.dbcompanion.service.DatabaseService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import static org.assertj.core.api.Assertions.assertThat;

class DashboardReadinessTest {
    private DatabaseService database(List<AiProfile> profiles, boolean fail) {
        return new DatabaseService(null, new SessionDataSource(), null, null, null) {
            @Override public ProfilePage profiles(PoolSession session, String name) {
                if (fail) throw new IllegalStateException("synthetic read failure");
                return new ProfilePage(profiles, List.of());
            }
        };
    }
    @Test void prefersEnabledProfileAndReusesDetailedDiagnosis() {
        var service = new AiSetupService(database(List.of(
                new AiProfile("OFF", "DISABLED", "", "", "", ""),
                new AiProfile("ON", "ENABLED", "", "", "", "")), false), null, null, null) {
            @Override public List<Check> diagnose(PoolSession session, String profile, String purpose) {
                assertThat(profile).isEqualTo("ON");
                assertThat(purpose).isEqualTo("nl2sql");
                return List.of(new Check("credential", State.UNKNOWN, "/db/credentials"));
            }
        };
        try (var session = new OntologyReadCacheTest().session()) {
            var summary = service.summary(session);
            assertThat(summary.profile()).isEqualTo("ON");
            assertThat(summary.checks()).containsExactly(new AiSetupService.Check("credential", AiSetupService.State.UNKNOWN, "/db/credentials"));
        }
    }
    @Test void missingProfilesAndFailedReadsRemainDistinct() {
        try (var session = new OntologyReadCacheTest().session()) {
            assertThat(new AiSetupService(database(List.of(), false), null, null, null).summary(session).checks())
                    .extracting(AiSetupService.Check::state).containsExactly(AiSetupService.State.ACTION);
            assertThat(new AiSetupService(database(List.of(), true), null, null, null).summary(session).checks())
                    .extracting(AiSetupService.Check::state).containsExactly(AiSetupService.State.UNKNOWN);
        }
    }
    @Test void foreignSchemaNeverQueries() {
        try (var session = new OntologyReadCacheTest().session()) {
            session.metadata().selectSchema("OTHER");
            assertThat(new AiSetupService(null, null, null, null).summary(session).checks())
                    .extracting(AiSetupService.Check::key).containsExactly("owner");
        }
    }
    @Test void expiredSessionReturnsUnauthorizedWithoutCreatingSession() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        new AiSetupController(null, null).summary(request, response, new ExtendedModelMap());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(request.getSession(false)).isNull();
    }
    @Test void summaryEscapesProfileAndRendersAllStatesInFourLanguages() throws java.io.IOException {
        var resolver = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new org.thymeleaf.spring6.SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c, String base, java.util.Map<String,Object> parameters) { return ""; }
        });
        for (String language : List.of("ko", "en", "ja", "zh-CN")) {
            var context = new org.thymeleaf.context.Context(java.util.Locale.forLanguageTag(language));
            context.setVariable("summary", new AiSetupService.Summary("P&<test>", List.of(
                    new AiSetupService.Check("model", AiSetupService.State.CONFIGURED, "/ai-profiles"),
                    new AiSetupService.Check("provider", AiSetupService.State.ACTION, "/ai-profiles"),
                    new AiSetupService.Check("execution", AiSetupService.State.UNKNOWN, "/ai-test"))));
            assertThat(engine.process("fragments/setup-summary", context))
                    .contains("P&amp;&lt;test&gt;", "app-setup-CONFIGURED", "app-setup-ACTION", "app-setup-UNKNOWN", "profile=P%26%3Ctest%3E")
                    .doesNotContain("??", "th:", "<test>");
            context.setVariable("_csrf", new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "synthetic"));
            context.setVariable("info", new com.dbcompanion.model.DatabaseInfo("APP", "APP", "LOW", "SYNTHETIC"));
            context.setVariable("schemas", List.of("APP"));
            context.setVariable("selectedSchema", "APP");
            context.setVariable("alias", "SYNTHETIC_LOW");
            context.setVariable("activePage", "overview");
            context.setVariable("appBuild", java.util.Map.of("version", "test", "id", "synthetic"));
            var dashboard = engine.process("dashboard", context);
            assertThat(dashboard).contains("data-dashboard-readiness", "/ai-setup/summary", "/tables", "/ai-test")
                    .doesNotContain("??", "th:");
            if (language.equals("ko") && System.getProperty("dashboard.preview") != null) {
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("dashboard.preview")),
                        dashboard.replace("</head>", "<base href=\"http://127.0.0.1:18087/\"></head>"));
            }
        }
    }
}
