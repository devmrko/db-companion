package com.dbcompanion;

import com.dbcompanion.model.AgentCatalog.Item;
import com.dbcompanion.model.DatabaseInfo;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class ToolListLayoutTemplateTest {
    static final String NAME = "APP_" + "LONG_TOOL_".repeat(8);
    static String render(String language, String mode) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c, String base, Map<String, Object> params) { return ""; }
        });
        String kind = mode.equals("agent") ? "AGENT" : mode.equals("task") ? "TASK" : "TOOL";
        boolean owner = !mode.equals("readonly");
        String text = "합성 설명 · Synthetic · テスト · 测试\n" + "LONG_DESCRIPTION_".repeat(16) + "<script>not-code</script>";
        var objects = IntStream.rangeClosed(1, 12).mapToObj(i -> new Item(String.valueOf(i), i == 1 ? NAME : i == 2 ? "TOOL_\"<unsafe>&" : "TOOL_" + i,
                i == 1 ? text : i == 3 ? null : "합성 테스트 · Synthetic tool description " + i, "ENABLED", null, null)).toList();
        var context = new Context(Locale.forLanguageTag(language));
        context.setVariable("_csrf", new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "synthetic-not-valid"));
        context.setVariable("info", new DatabaseInfo(owner ? "APP" : "READER", "APP", "LOW", "SYNTHETIC"));
        context.setVariable("schemas", List.of("APP")); context.setVariable("selectedSchema", "APP");
        context.setVariable("activePage", "agents"); context.setVariable("objectKind", kind);
        if (mode.equals("error")) context.setVariable("loadError", "Synthetic unavailable " + text);
        else context.setVariable("objects", mode.equals("empty") ? List.of() : objects);
        return engine.process("ai-agent-objects", context);
    }
    @ParameterizedTest @ValueSource(strings = {"ko", "en", "ja", "zh-CN"})
    void toolListPreservesNamesDescriptionsAndPaging(String language) {
        assertThat(render(language, "list")).contains("app-shell app-tool-list-page", NAME, "LONG_DESCRIPTION_".repeat(16),
                "&lt;script&gt;not-code&lt;/script&gt;", "TOOL_&quot;&lt;unsafe&gt;&amp;", "aria-label=\"TOOL\"", "scope=\"col\"",
                "kind=TOOL", "schema=APP", "data-ai-create=\"TOOL\"", "data-ai-creation", "data-list-row", "data-page-next", "data-page-prev")
                .doesNotContain("<script>not-code</script>", "??ui.", "th:");
    }
    @Test void readOnlyListKeepsDetailLinksWithoutCreation() {
        assertThat(render("ko", "readonly")).contains(NAME, "/ai-agents/object?", "data-paged-list", "app-tool-list-page")
                .doesNotContain("data-ai-create=", "data-ai-creation");
    }
    @Test void emptyAndFailedListsAreDistinct() {
        assertThat(render("ko", "empty")).contains("조회된 기록이 없습니다.", "data-list-controls").doesNotContain("data-list-row");
        assertThat(render("en", "error")).contains("Synthetic unavailable", "app-tool-list-page").doesNotContain("data-paged-list");
    }
    @ParameterizedTest @ValueSource(strings = {"agent", "task"})
    void otherKindsDoNotAcquireToolLayout(String mode) {
        assertThat(render("en", mode)).contains("kind=" + mode.toUpperCase(Locale.ROOT), "data-list-row").doesNotContain("app-tool-list-page");
    }
}
