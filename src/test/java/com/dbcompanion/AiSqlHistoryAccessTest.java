package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.controller.AiMappedSqlController;
import com.dbcompanion.controller.AiSqlHistoryController;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.DatabaseSession;
import com.dbcompanion.service.AiSqlHistoryAccessService;
import com.dbcompanion.service.AiSqlHistoryAccessService.Access;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import static org.assertj.core.api.Assertions.*;

class AiSqlHistoryAccessTest {
    @Test void capabilityProbesReadNoHistoryAndNeverUseCallerIdentifiers() {
        for (String name : List.of("mapping", "cache", "awr", "audit", "policies")) {
            for (String sql : AiSqlHistoryAccessService.probes(name, "AUDSYS.UNIFIED_AUDIT_TRAIL")) {
                assertThat(sql).startsWith("SELECT ").endsWith(" WHERE 1=0")
                        .doesNotContain("COUNT(", "GRANT", "CREATE", "EXECUTE", "SQL_BINDS");
            }
        }
        assertThatThrownBy(() -> AiSqlHistoryAccessService.probes("audit", "APP.UNIFIED_AUDIT_TRAIL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiSqlHistoryAccessService.probes("user_input", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Access.denied(942).state()).isEqualTo("check");
        assertThat(Access.denied(1031).state()).isEqualTo("required");
        assertThat(Access.denied(1013).state()).isEqualTo("unknown");
        assertThat(Access.allowed().available()).isTrue();
    }
    @Test void inaccessibleSourcesDoNotCallListServicesEvenWithExplicitLoad() {
        for (String user : List.of("ADMIN", "APP")) {
            try (var session = session(user)) {
                var request = new MockHttpServletRequest();
                request.getSession().setAttribute(PoolSession.ATTRIBUTE, session);
                var response = new MockHttpServletResponse();
                var model = new ExtendedModelMap();
                model.addAttribute("sqlCapabilities", Map.of("mapping", Access.denied(942), "audit", Access.denied(1031), "policies", Access.denied(1031)));
                assertThat(new AiMappedSqlController(null).page("", "", "1", request, response, model)).isEqualTo("ai-mapped-sql");
                assertThat(model.get("sqlSourceBlocked")).isEqualTo(true);
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(model).doesNotContainKeys("loadError", "history");
                assertThat(new AiSqlHistoryController(null).page("audit", "generate", "", "", "", "", "", "1", true, false, false, true,
                        request, response, model)).isEqualTo("ai-sql-history");
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(model).doesNotContainKeys("result", "policies", "inputError");
            }
        }
    }
    @Test void navigationHidesUnreadableSourcesButKeepsAccessExplanation() {
        var resolver = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new org.thymeleaf.spring6.SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c, String base, Map<String, Object> p) { return ""; }
        });
        var context = new org.thymeleaf.context.Context(java.util.Locale.KOREAN);
        context.setVariable("current", "cache"); context.setVariable("sqlSourceBlocked", true);
        context.setVariable("sqlCapabilities", Map.of("mapping", Access.denied(942), "cache", Access.allowed(), "awr", Access.denied(1031), "audit", Access.denied(942), "policies", Access.denied(1031)));
        String html = engine.process("fragments/sql-history-sources", context);
        assertThat(html).contains("href=\"/ai-executions/sql/sources?source=cache\"", "data-source=\"audit\"", "data-capability=\"check\"", "조회 원천 접근 상태")
                .doesNotContain("href=\"/ai-executions/sql\"", "source=audit", "source=awr");
        context.setVariable("sqlCapabilities", Map.of("mapping", Access.allowed(), "cache", Access.allowed(), "awr", Access.allowed(), "audit", Access.allowed(), "policies", Access.allowed()));
        assertThat(engine.process("fragments/sql-history-sources", context)).contains("source=audit", "source=awr", "href=\"/ai-executions/sql\"");
    }
    @Test void cacheDefaultsToLoginNotSelectedSchemaButExplicitEmptyScopeIsPreserved() {
        try (var session = session("APP")) {
            var request = new MockHttpServletRequest();
            request.getSession().setAttribute(PoolSession.ATTRIBUTE, session);
            for (String source : List.of("cache", "awr", "audit")) {
                for (String actor : new String[]{null, "", "OTHER"}) {
                    if (source.equals("awr") && "OTHER".equals(actor)) continue;
                    var model = new ExtendedModelMap();
                    model.addAttribute("sqlCapabilities", Map.of(source, Access.allowed()));
                    new AiSqlHistoryController(null).page(source, "all", "", "", "", "", actor, "1", false, false, false, false,
                            request, new MockHttpServletResponse(), model);
                    var query = (com.dbcompanion.model.AiSqlHistory.Query) model.get("q");
                    String expected = actor == null ? (source.equals("cache") ? "APP" : "") : actor;
                    assertThat(query.actor()).isEqualTo(expected);
                    assertThat(model).doesNotContainKeys("result", "inputError");
                }
            }
        }
    }
    private PoolSession session(String user) {
        var session = new PoolSession(new HikariDataSource(), "LOW", () -> {});
        session.initialize(new DatabaseSession(new DatabaseInfo(user, "OTHER", "LOW", "DB"), List.of(user, "OTHER")));
        return session;
    }
}
