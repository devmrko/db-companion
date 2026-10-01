package com.dbcompanion;

import com.dbcompanion.common.db.AgentObjectEditPolicy;
import com.dbcompanion.model.AgentCatalog.*;
import com.dbcompanion.model.DatabaseInfo;
import java.util.HashMap;
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

class TaskDetailLayoutTemplateTest {
    static final String NAME = "APP_" + "LONG_TASK_".repeat(8);
    static String render(String language, String mode) {
        boolean linked = mode.startsWith("linked-");
        String state = mode.substring(mode.indexOf('-') + 1);
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c, String base, Map<String, Object> params) { return ""; }
        });
        String text = "합성 설명 · Synthetic · テスト · 测试 " + "LONG_INSTRUCTION_".repeat(70) + "<script>not-code</script>";
        String input = "APP_" + "LONG_INPUT_TASK_".repeat(7), tool = "APP_" + "LONG_TOOL_".repeat(8);
        var attributes = List.of(new Attribute(NAME, "instruction", text, "2026-01-01"), new Attribute(NAME, "input", input, "2026-01-01"),
                new Attribute(NAME, "tools", "[\"" + tool + "\",\"TOOL_TWO\"]", "2026-01-01"), new Attribute(NAME, "enable_human_tool", "false", "2026-01-01"), new Attribute(NAME, "unknown", text, null));
        boolean owner = !state.equals("readonly");
        var context = new Context(Locale.forLanguageTag(language));
        context.setVariable("_csrf", new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "synthetic-not-valid"));
        context.setVariable("info", new DatabaseInfo(owner ? "APP" : "READER", "APP", "LOW", "SYNTHETIC"));
        context.setVariable("schemas", List.of("APP")); context.setVariable("selectedSchema", "APP");
        context.setVariable("activePage", "agents"); context.setVariable("targetSchema", "APP");
        context.setVariable("objectKind", "TASK"); context.setVariable("objectName", NAME); context.setVariable("objectEditable", owner);
        context.setVariable("taskName", NAME); context.setVariable("teamName", "APP_" + "LONG_TEAM_".repeat(8));
        var editable = new HashMap<String, Set<String>>();
        AgentObjectEditPolicy.EDITABLE.forEach((k, attrs) -> editable.put(k.name(), attrs));
        context.setVariable("editableObjectAttributes", editable);
        if (state.equals("error")) context.setVariable("loadError", "Synthetic unavailable " + text);
        else {
            var component = new Component(NAME, new Item("1", NAME, text, "ENABLED", "2026-01-01", "2026-01-02"), state.equals("empty") ? List.of() : attributes);
            context.setVariable("component", component);
            var tools = List.of(new Component(tool, new Item("2", tool, text, "ENABLED", "2026-01-01", "2026-01-02"), List.of(new Attribute(tool, "instruction", text, "2026-01-01"))), new Component("MISSING_" + tool, null, List.of()));
            context.setVariable("taskPage", new TaskPage(component, state.equals("empty") ? List.of() : tools));
        }
        return engine.process(linked ? "ai-agent-task" : "ai-agent-object", context);
    }
    @ParameterizedTest @ValueSource(strings = {"ko", "en", "ja", "zh-CN"})
    void bothRoutesPreserveEscapedValuesAndTaskEditingHooks(String language) {
        for (String route : List.of("standalone", "linked")) {
            assertThat(render(language, route + "-detail")).contains("app-shell app-task-detail-page", NAME, "LONG_INSTRUCTION_".repeat(70),
                    "&lt;script&gt;not-code&lt;/script&gt;", "data-object-kind=\"TASK\"", "data-edit-object-attribute=\"instruction\"",
                    "data-edit-object-attribute=\"input\"", "data-edit-object-attribute=\"tools\"", "data-edit-object-attribute=\"enable_human_tool\"", "data-object-editor", "data-object-history")
                    .doesNotContain("<script>not-code</script>", "??ui.", "th:", "data-edit-object-attribute=\"unknown\"", "app-agent-detail-page");
        }
    }
    @ParameterizedTest @ValueSource(strings = {"standalone", "linked"})
    void readOnlyAndUnavailableStatesKeepEditingUnavailable(String route) {
        assertThat(render("ko", route + "-readonly")).contains(NAME, "app-task-detail-page", "LONG_INSTRUCTION_")
                .doesNotContain("data-object-editor", "data-object-history", "data-edit-object-attribute", "data-ai-create=");
        assertThat(render("en", route + "-error")).contains("Synthetic unavailable", "app-task-detail-page").doesNotContain("data-object-editor", "data-object-history");
        assertThat(render("ko", route + "-empty")).contains("등록된 속성이 없습니다.").doesNotContain("data-edit-object-attribute");
    }
    @Test void linkedToolsAndBackLinksArePreservedWithoutAddingStandaloneClone() {
        assertThat(render("ko", "linked-detail")).contains("data-object-kind=\"TOOL\"", "MISSING_APP_", "참조된 항목이 없거나 조회 권한이 없습니다.", "/ai-agents/team?schema=APP", "/ai-agents/task?schema=APP")
                .doesNotContain("data-ai-create=");
        assertThat(render("ko", "linked-empty")).contains("연결된 Tool이 없습니다.");
        assertThat(render("en", "standalone-detail")).contains("/ai-agents/objects?kind=TASK", "data-ai-create=\"TASK\"").doesNotContain("data-object-kind=\"TOOL\"");
    }
}
