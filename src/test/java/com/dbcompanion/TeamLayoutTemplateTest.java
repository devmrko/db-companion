package com.dbcompanion;

import com.dbcompanion.model.AgentCatalog.*;
import com.dbcompanion.model.DatabaseInfo;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class TeamLayoutTemplateTest {
    static final String NAME = "APP_" + "LONG_TEAM_".repeat(8);
    static String render(String language, String mode) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c, String base, Map<String, Object> params) { return ""; }
        });
        String text = "합성 설명 · Synthetic · テスト · 测试 " + "LONG_VALUE_".repeat(70) + "<script>not-code</script>";
        String agent = "APP_" + "LONG_AGENT_".repeat(8), task = "APP_" + "LONG_TASK_".repeat(8);
        var attributes = List.of(new Attribute(NAME, "agents", "[{\"name\":\"" + agent + "\",\"task\":\"" + task + "\"}]", "2026-01-01"),
                new Attribute(NAME, "process", "sequential", "2026-01-01"), new Attribute(NAME, "supervisor_agent", agent, "2026-01-01"),
                new Attribute(NAME, "long_term_memory_length", "10", "2026-01-01"));
        var team = new Component(NAME, new Item("1", NAME, text, "ENABLED", "2026-01-01", "2026-01-02"), attributes);
        var member = new Component(agent, new Item("2", agent, text, "ENABLED", "2026-01-01", "2026-01-02"), List.of(new Attribute(agent, "role", text, "2026-01-01")));
        boolean owner = !mode.equals("readonly"), empty = mode.equals("empty");
        var context = new Context(Locale.forLanguageTag(language));
        context.setVariable("_csrf", new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "synthetic-not-valid"));
        context.setVariable("info", new DatabaseInfo(owner ? "APP" : "READER", "APP", "LOW", "SYNTHETIC"));
        context.setVariable("schemas", List.of("APP")); context.setVariable("selectedSchema", "APP");
        context.setVariable("activePage", "agents"); context.setVariable("targetSchema", "APP"); context.setVariable("teamName", NAME);
        context.setVariable("teamEditable", owner); context.setVariable("objectEditable", owner);
        context.setVariable("editableTeamAttributes", Set.of("agents", "process", "supervisor_agent", "long_term_memory_length"));
        context.setVariable("editableObjectAttributes", Map.of("AGENT", Set.of("role")));
        if (mode.equals("error")) context.setVariable("loadError", "Synthetic unavailable " + text);
        else context.setVariable("teamPage", new TeamPage(empty ? new Component(NAME, team.info(), List.of()) : team,
                empty ? List.of() : List.of(member, new Component("MISSING_REFERENCE", null, List.of())),
                empty ? List.of() : List.of(new TaskRow(agent, task, new Item("3", task, text, "ENABLED", null, null)), new TaskRow(agent, "MISSING_TASK", null)), agent));
        return engine.process("ai-agent-team", context);
    }
    @ParameterizedTest @ValueSource(strings = {"ko", "en", "ja", "zh-CN"})
    void detailPreservesEscapedNamesAttributesAndActions(String language) {
        assertThat(render(language, "detail")).contains("app-shell app-team-detail-page", "app-team-heading-actions", NAME,
                "LONG_VALUE_".repeat(70), "&lt;script&gt;not-code&lt;/script&gt;", "app-card table-responsive",
                "data-edit-team-attribute=\"agents\"", "data-team-editor", "data-team-history", "data-object-editor", "data-object-history",
                "Supervisor Agent", "MISSING_REFERENCE", "MISSING_TASK")
                .doesNotContain("<script>not-code</script>", "??ui.", "th:");
    }
    @Test void readOnlyDetailKeepsContentWithoutMutationActions() {
        assertThat(render("ko", "readonly")).contains(NAME, "LONG_VALUE_", "app-team-detail-page")
                .doesNotContain("data-team-editor", "data-team-history", "data-object-editor", "data-open-object-history", "data-edit-team-attribute", "data-ai-create=\"TEAM\"");
    }
    @Test void emptyAndFailedReadsStillRender() {
        assertThat(render("ko", "empty")).contains("연결된 Agent가 없습니다.", "연결된 Task가 없습니다.");
        assertThat(render("en", "error")).contains("Synthetic unavailable", "app-team-detail-page").doesNotContain("data-team-editor", "data-team-history");
    }
}
