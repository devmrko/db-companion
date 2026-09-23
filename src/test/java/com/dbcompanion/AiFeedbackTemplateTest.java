package com.dbcompanion;

import com.dbcompanion.model.AiFeedback.*;
import com.dbcompanion.model.AiProfile;
import com.dbcompanion.model.DatabaseInfo;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class AiFeedbackTemplateTest {
    private final List<AiProfile> profiles = List.of(new AiProfile("P", "ENABLED", "", "", "1", ""));
    private String render(Result result, boolean detail, String profile, String error) {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source()); engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext context, String base, java.util.Map<String,Object> parameters) { return ""; }
        });
        var context = new Context(java.util.Locale.KOREAN);
        context.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","render-only"));
        context.setVariable("info", new DatabaseInfo("APP", "APP", "LOW", "DB"));
        context.setVariable("schemas", List.of("APP", "OTHER")); context.setVariable("selectedSchema", "APP");
        context.setVariable("activePage", "feedback"); context.setVariable("detailMode", detail);
        context.setVariable("profile", profile); context.setVariable("search", "<script>q</script>"); context.setVariable("type", "negative");
        context.setVariable("pageNumber", 2); context.setVariable("result", result); context.setVariable("loadError", error);
        return engine.process("ai-feedback", context);
    }
    @Test void firstVisitOnlyOffersProfilesAndNoEmptyResultClaim() {
        String html = render(new Result(profiles, false, null, null), false, "", null);
        assertThat(html).contains("Select AI Feedback", "프로필을 선택하세요.", "value=\"P\"", "value=\"/ai-feedback\"", "data-query-select")
                .doesNotContain("조회된 Feedback이 없습니다.", "th:", "<script>q</script>");
    }
    @Test void listHasEscapedPreviewsAndPreservesFiltersAndPages() {
        var page = new Page(List.of(new Item("AAABBBCCC000001abc", "<img onerror=x>", "negative", "설명")), 2, true);
        String html = render(new Result(profiles, false, page, null), false, "P", null);
        assertThat(html).contains("page=1", "page=3", "id=AAABBBCCC000001abc", "profile=P", "type=negative", "search=", "title=\"&lt;img", "부정")
                .contains("data-feedback-editor","data-fe-save disabled")
                .doesNotContain("<img onerror=x>", "원문 JSON", "th:");
    }
    @Test void missingStorageEmptyResultsAndErrorsAreSeparate() {
        assertThat(render(new Result(profiles, true, null, null), false, "P", null)).contains("저장 테이블이 없거나 조회할 수 없습니다.")
                .doesNotContain("조회된 Feedback이 없습니다.");
        assertThat(render(new Result(profiles, false, new Page(List.of(), 1, false), null), false, "P", null)).contains("조회된 Feedback이 없습니다.");
        assertThat(render(null, false, "P", "ORA-00942: <table>")).contains("ORA-00942: &lt;table&gt;")
                .doesNotContain("조회된 Feedback이 없습니다.", "저장 테이블이 없거나");
    }
    @Test void fullDetailKeepsLongContentAndNullsAndReturnFilters() {
        String longSql = "한글😀".repeat(6000) + "<script>TAIL</script>";
        var detail = new Detail("Q", "positive", longSql, null, null, "select ai Q", "{\"extra\":\"value\"}");
        String html = render(new Result(profiles, false, null, detail), true, "P", null);
        assertThat(html).contains("한글😀".repeat(6000), "&lt;script&gt;TAIL&lt;/script&gt;", "응답 SQL", "원문 JSON", "extra", "page=2", "← 목록")
                .doesNotContain("<script>TAIL</script>", "name=\"search\"", "DBMS_CLOUD_AI.FEEDBACK", "th:");
    }
    @Test void selectedProfileOffersInitiallyDisabledTrackingControlsWithoutAutoInstallation() {
        String html=render(new Result(profiles,false,new Page(List.of(),1,false),null),false,"P",null);
        assertThat(html).contains("data-feedback-tracking","data-profile=\"P\"","트리거 설치·켜기","공통 이력 준비","data-ft-state", "data-ft-action=\"install\" hidden disabled")
                .doesNotContain("트리거 켜짐","th:");
        assertThat(render(new Result(profiles,false,null,null),false,"",null)).doesNotContain("data-feedback-tracking");
    }
}
