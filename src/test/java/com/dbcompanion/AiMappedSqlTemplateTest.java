package com.dbcompanion;

import com.dbcompanion.model.AiMappedSql.*;
import com.dbcompanion.model.AiMappedSql;
import com.dbcompanion.model.DatabaseInfo;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class AiMappedSqlTemplateTest {
    private String render(Page page, String error) {
        return render(page, error, null);
    }
    private String render(Page page, String error, String details) {
        return render(page, error, details, page == null ? Access.NOT_CHECKED : Access.AVAILABLE, "APP");
    }
    private String render(Page page, String error, String details, Access access, String user) {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source()); engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext context, String base, java.util.Map<String,Object> parameters) { return ""; }
        });
        var context = new Context(java.util.Locale.KOREAN); context.setVariable("info", new DatabaseInfo(user, "OTHER", "LOW", "DB"));
        context.setVariable("activePage", "executions"); context.setVariable("sqlId", ""); context.setVariable("question", "<script>q</script>");
        context.setVariable("history", page); context.setVariable("loadError", error);
        context.setVariable("loadErrorDetails", details);
        context.setVariable("sqlAccess", access);
        context.setVariable("readGrantExample", AiMappedSql.readGrantExample(user));
        return engine.process("ai-mapped-sql", context);
    }
    @Test void completePageHasTabsGrantScopePagingAndNoFalseSchemaFilter() {
        String html = render(new Page(List.of(new Item("key", "date", "123456789abcd", "AI", "질문")), 2, true), null);
        assertThat(html).contains("저장된 대화", "Select AI SQL", "메모리", "DB 권한 범위", "page=1", "page=3", "data-execution-mode=\"sql\"", "2 페이지")
                .doesNotContain("th:", "name=\"schema\"", "name=\"profile\"", "<script>q</script>", "조회된 SQL 매핑이 없습니다.");
    }
    @Test void promptsAreEscapedAndFullSqlIsNotPutInListHtml() {
        String html = render(new Page(List.of(new Item("key", null, "123456789abcd", null, "<script>question</script>")), 1, false), null);
        assertThat(html).contains("&lt;script&gt;question", "data-execution-id=\"key\"", "data-execution-prompt", "data-execution-response")
                .doesNotContain("<script>question</script>", "textarea", "재실행", "method=\"post\" action=\"/ai-executions");
    }
    @Test void emptyAndOracleErrorsRemainDistinct() {
        assertThat(render(new Page(List.of(), 1, false), null)).contains("조회된 SQL 매핑이 없습니다.");
        assertThat(render(null, "ORA-00942: <view>")).contains("ORA-00942: &lt;view&gt;").doesNotContain("조회된 SQL 매핑이 없습니다.");
    }
    @Test void accessGuidanceKeepsEscapedRawEvidenceInExpandableDetails() {
        String html = render(null, "계정별 안내", "Oracle code=942 · ORA-00942: <view>");
        assertThat(html).contains("계정별 안내", "<summary>오류 상세</summary>", "Oracle code=942", "&lt;view&gt;")
                .doesNotContain("<view>", "조회된 SQL 매핑이 없습니다.", "<details open");
    }
    @Test void successfulEmptyListConfirmsAccessForAdminAndOrdinaryUsers() {
        for (String user : List.of("ADMIN", "DEMO_APP")) {
            String html = render(new Page(List.of(), 1, false), null, null, Access.AVAILABLE, user);
            assertThat(html).contains("data-access=\"available\">조회 가능</span>", "조회된 SQL 매핑이 없습니다.",
                    "aria-label=\"SQL 조회 권한 안내\"", "popovertarget=\"mapped-sql-access-help\"");
        }
    }
    @Test void permissionHelpProvidesAnAdminCommandForLoginUserNotSelectedSchema() {
        String html = render(null, "권한 확인", "ORA-00942", Access.CHECK, "DEMO_APP");
        assertThat(html).contains("data-access=\"check\">접근 확인 필요</span>",
                "ADMIN으로 접속해", "GRANT READ ON SYS.V_$MAPPED_SQL TO &quot;DEMO_APP&quot;;",
                "권한 부족 또는 뷰 미지원", "이 화면의 새로고침", "본인 스키마의 기록만")
                .doesNotContain("TO &quot;OTHER&quot;", "DB_USER", "V_$SESSION", "WITH GRANT OPTION", "조회된 SQL 매핑이 없습니다.");
        String admin = render(null, "권한 확인", "ORA-01031", Access.REQUIRED, "ADMIN");
        assertThat(admin).contains("data-access=\"required\">권한 필요</span>", "TO &quot;DB_USER&quot;;", "실제 조회할 로그인 계정명으로")
                .doesNotContain("TO &quot;ADMIN&quot;", "조회된 SQL 매핑이 없습니다.");
    }
    @Test void unknownAndNotCheckedAreNotReportedAsDeniedAndGrantTextIsEscaped() {
        for (Access access : List.of(Access.UNKNOWN, Access.NOT_CHECKED)) {
            String html = render(null, "입력 또는 연결 확인", null, access, "APP\"><script>alert(1)</script>");
            assertThat(html).contains("data-access=\"unknown\">" + access.getLabel() + "</span>", "&lt;script&gt;")
                    .doesNotContain("<script>alert(1)</script>", "data-access=\"required\"", "조회된 SQL 매핑이 없습니다.");
        }
    }
}
