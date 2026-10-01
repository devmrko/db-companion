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

class AgentDetailLayoutTemplateTest {
    static final String NAME = "APP_" + "LONG_AGENT_".repeat(8);
    static String render(String language, String mode) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c, String base, Map<String, Object> params) { return ""; }
        });
        String text = "합성 설명 · Synthetic · テスト · 测试 " + "LONG_ROLE_".repeat(70) + "<script>not-code</script>";
        String profile = "APP_" + "LONG_PROFILE_".repeat(7), tool = "APP_" + "LONG_TOOL_".repeat(8);
        var attributes = List.of(new Attribute(NAME, "role", text, "2026-01-01"), new Attribute(NAME, "profile_name", profile, "2026-01-01"),
                new Attribute(NAME, "tools", "[\"" + tool + "\",\"TOOL_TWO\"]", "2026-01-01"), new Attribute(NAME, "enable_human_tool", "false", "2026-01-01"),
                new Attribute(NAME, "short_term_memory_length", "10", "2026-01-01"), new Attribute(NAME, "supervisor", "true", "2026-01-01"), new Attribute(NAME, "unknown", text, null));
        boolean owner = !mode.equals("readonly");
        String kind = mode.equals("task") ? "TASK" : mode.equals("tool") ? "TOOL" : "AGENT";
        var context = new Context(Locale.forLanguageTag(language));
        context.setVariable("_csrf", new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "synthetic-not-valid"));
        context.setVariable("info", new DatabaseInfo(owner ? "APP" : "READER", "APP", "LOW", "SYNTHETIC"));
        context.setVariable("schemas", List.of("APP")); context.setVariable("selectedSchema", "APP");
        context.setVariable("activePage", "agents"); context.setVariable("targetSchema", "APP");
        context.setVariable("objectKind", kind); context.setVariable("objectName", NAME); context.setVariable("objectEditable", owner);
        var editable = new HashMap<String, Set<String>>();
        AgentObjectEditPolicy.EDITABLE.forEach((k, attrs) -> editable.put(k.name(), attrs));
        context.setVariable("editableObjectAttributes", editable);
        if (mode.equals("error")) context.setVariable("loadError", "Synthetic unavailable " + text);
        else context.setVariable("component", new Component(NAME, new Item("1", NAME, text, "ENABLED", "2026-01-01", "2026-01-02"), mode.equals("empty") ? List.of() : attributes));
        return engine.process("ai-agent-object", context);
    }
    @ParameterizedTest @ValueSource(strings = {"ko", "en", "ja", "zh-CN"})
    void detailPreservesEscapedValuesAndEditorHistoryHooks(String language) {
        assertThat(render(language, "detail")).contains("app-shell app-agent-detail-page", NAME, "LONG_ROLE_".repeat(70),
                "&lt;script&gt;not-code&lt;/script&gt;", "data-object-kind=\"AGENT\"", "data-edit-object-attribute=\"role\"",
                "data-edit-object-attribute=\"profile_name\"", "data-edit-object-attribute=\"tools\"", "data-object-editor", "data-object-history", "data-ai-create=\"AGENT\"")
                .doesNotContain("<script>not-code</script>", "??ui.", "th:", "data-edit-object-attribute=\"supervisor\"", "data-edit-object-attribute=\"unknown\"");
    }
    @Test void readOnlyDetailDoesNotOfferEditingOrCloning() {
        assertThat(render("ko", "readonly")).contains(NAME, "app-agent-detail-page", "LONG_ROLE_")
                .doesNotContain("data-object-editor", "data-object-history", "data-edit-object-attribute", "data-ai-create=");
    }
    @Test void emptyAndFailedReadsRenderSafely() {
        assertThat(render("ko", "empty")).contains("등록된 속성이 없습니다.").doesNotContain("data-edit-object-attribute");
        assertThat(render("en", "error")).contains("Synthetic unavailable", "app-agent-detail-page").doesNotContain("data-object-editor", "data-object-history");
    }
    @ParameterizedTest @ValueSource(strings = {"task", "tool"})
    void otherObjectDetailsDoNotAcquireAgentStyle(String mode) {
        assertThat(render("en", mode)).contains("data-object-kind=\"" + mode.toUpperCase(Locale.ROOT) + "\"").doesNotContain("app-agent-detail-page");
    }
}
