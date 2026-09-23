package com.dbcompanion;

import com.dbcompanion.model.AiExecutionHistory.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class AiExecutionHistoryTemplateTest {
    @Test void completePageRendersMenuFiltersAndNavigationWithRealThymeleaf() {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source()); engine.setTemplateResolver(resolver);
        // Pure renderer uses the app's root context path, with no servlet/session/database stand-ins.
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext context, String base, java.util.Map<String,Object> parameters) { return ""; }
        });
        var context = new Context(java.util.Locale.KOREAN);
        context.setVariable("info", new com.dbcompanion.model.DatabaseInfo("APP", "APP", "LOW", "DB"));
        context.setVariable("schemas", List.of("APP", "OTHER")); context.setVariable("selectedSchema", "APP"); context.setVariable("activePage", "executions");
        context.setVariable("from", "2026-09-11"); context.setVariable("to", "2026-09-17");
        context.setVariable("profile", "P"); context.setVariable("question", "<script>q</script>");
        context.setVariable("history", new Page(List.of(new Item("abc-123", "date", "P", "chat", "질문")), 2, true));
        String html = engine.process("ai-executions", context);
        assertThat(html).contains("AI 실행 이력", "저장된 대화", "Select AI SQL", "conversation=true", "대화 ID", "aria-current=\"page\"", "name=\"returnTo\" value=\"/ai-executions\"", "page=1", "page=3", "data-schema=\"APP\"", "2 페이지")
                .doesNotContain("th:", "<script>q</script>", "조회된 기록이 없습니다.");
    }
    private String render(String selector, Page page, String error) {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source()); engine.setTemplateResolver(resolver);
        var context = new Context(java.util.Locale.KOREAN); context.setVariable("history", page); context.setVariable("loadError", error);
        return engine.process("ai-executions", Set.of(selector), context);
    }
    @Test void listEscapesQuestionAndProfileAndOnlyProvidesDetailId() {
        var page = new Page(List.of(new Item("abc-123", "date", "<script>profile</script>", "chat", "<script>question</script>\n한글")), 1, false);
        assertThat(render("//table", page, null)).contains("&lt;script&gt;", "한글", "data-execution-id=\"abc-123\"")
                .doesNotContain("<script>", "th:text=", "PROMPT_RESPONSE");
    }
    @Test void emptyAndErrorStatesAreSeparate() {
        assertThat(render("//table", new Page(List.of(), 1, false), null)).contains("조회된 기록이 없습니다.");
        assertThat(render("//div[@role='alert']", null, "ORA-00942: <view>")).contains("ORA-00942: &lt;view&gt;").doesNotContain("조회된 기록이 없습니다.");
    }
    @Test void lazyDetailDialogHasAllReadOnlyHooks() {
        String html = render("//dialog", null, null);
        for (String hook : List.of("dialog", "close", "message", "detail", "summary", "metadata", "prompt", "response")) assertThat(html).contains("data-execution-" + hook);
        assertThat(html).contains("질문·응답 상세", "접속·대화 정보", "hidden").doesNotContain("저장", "재실행", "textarea", "th:");
    }
}
