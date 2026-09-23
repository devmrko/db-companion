package com.dbcompanion;

import com.dbcompanion.model.AiSqlHistory.*;
import com.dbcompanion.model.DatabaseInfo;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class AiSqlHistoryTemplateTest {
    private String render(Source source, Page page, Policies policies, Locale locale) {
        return render(source,page,policies,locale,Match.all);
    }
    private String render(Source source, Page page, Policies policies, Locale locale, Match match) {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source()); engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext context, String base, java.util.Map<String,Object> parameters) { return ""; }
        });
        var context = new Context(locale); context.setVariable("info", new DatabaseInfo("ADMIN", "OTHER", "LOW", "DB"));
        context.setVariable("activePage", "executions"); context.setVariable("result", page); context.setVariable("policies", policies);
        context.setVariable("q", new Query(source,LocalDate.of(2026,9,15),LocalDate.of(2026,9,21),"","<script>filter</script>","",page==null?1:page.number(),match));
        context.setVariable("awrAllowed", false); context.setVariable("load", page != null);
        return engine.process("ai-sql-history",context);
    }
    private Page page(Failure failure) { return new Page(List.of(),1,false,Instant.parse("2026-09-21T00:00:00Z"),failure); }
    @Test void everySourceRendersInAllFourLanguagesWithNoMissingMessages() {
        for (var locale : List.of(Locale.KOREAN, Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.JAPANESE)) for(var source:Source.values()) {
            String html = render(source,null,null,locale);
            assertThat(html).contains("data-execution-mode=\"history\"", "source=cache", "source=awr", "source=audit", "data-execution-help")
                    .doesNotContain("??", "th:", "<script>filter</script>", "name=\"schema\"");
        }
    }
    @Test void initialVisitDoesNotClaimEmptySuccessOrAutomaticallyConfirmAwr() {
        String html = render(Source.awr,null,null,Locale.KOREAN);
        assertThat(html).contains("이 DB의 AWR 사용 권한", "name=\"awrAllowed\"", "required", "조건을 선택하고 검색")
                .doesNotContain("checked=", "조건에 맞는 기록이 없습니다.", "data-access=\"available\"");
    }
    @Test void emptyAndPermissionFailureAreDistinctAndErrorIsEscaped() {
        assertThat(render(Source.cache,page(null),null,Locale.KOREAN)).contains("조건에 맞는 기록이 없습니다.", "조회 가능");
        assertThat(render(Source.cache,page(new Failure(942,"ORA-00942 <view>")),null,Locale.KOREAN))
                .contains("접근 확인 필요", "ORA-00942 &lt;view&gt;").doesNotContain("조건에 맞는 기록이 없습니다.", "<view>");
        assertThat(render(Source.audit,page(new Failure(1031,"ORA-01031")),null,Locale.KOREAN)).contains("권한 필요");
    }
    @Test void pagingKeepsAllFiltersAndOnlyPreviewIsIncluded() {
        var row = new Item("safe-id","observed","123456789abcd","APP","CURSOR","0","<script>sql</script>",List.of("private-composite-key"));
        String html = render(Source.cache,new Page(List.of(row),2,true,Instant.now(),null),null,Locale.KOREAN);
        assertThat(html).contains("page=1", "page=3", "from=2026-09-15", "data-execution-id=\"safe-id\"", "&lt;script&gt;sql", "파싱 스키마", "마지막 활성 (DB)")
                .doesNotContain("<script>sql</script>", "private-composite-key", "name=\"awrAllowed\"");
    }
    @Test void auditDoesNotShowSqlIdFilterAndPolicyErrorsDoNotHideLogList() {
        String html = render(Source.audit,page(null),new Policies(List.of(),false,Instant.now(),new Failure(942,"ORA-00942 policy")),Locale.KOREAN);
        assertThat(html).contains("감사 시각 (UTC)", "반환 코드", "DB 사용자", "정책을 확인하지 못했습니다", "조건에 맞는 기록이 없습니다.")
                .doesNotContain("name=\"sqlId\"", "SQL ID</th>", "감사 OFF");
    }
    @Test void policyTargetConditionAndSuccessFailureAreVisibleWithoutClaimingEnabledForEveryone() {
        var fields = List.of(new Field("POLICY_NAME","P"),new Field("OBJECT_SCHEMA","SYS"),new Field("OBJECT_NAME","DBMS_CLOUD_AI"),
                new Field("AUDIT_OPTION","EXECUTE"),new Field("AUDIT_CONDITION","<condition>"),new Field("CONDITION_EVAL_OPT","SESSION"),
                new Field("AUDIT_ONLY_TOPLEVEL","YES"),new Field("ENABLED_OPTION","EXCEPT USER"),new Field("ENTITY_NAME","APP"),
                new Field("ENTITY_TYPE","USER"),new Field("SUCCESS","YES"),new Field("FAILURE","NO"));
        String html = render(Source.audit,null,new Policies(List.of(fields),true,Instant.now(),null),Locale.KOREAN);
        assertThat(html).contains("P · APP", "EXCEPT USER", "&lt;condition&gt;", "SUCCESS", "FAILURE", "200건").doesNotContain("<condition>");
    }
    @Test void generateSelectionPersistsInPaginationRefreshAndAllLanguages() {
        var row=new Item("id","observed","123456789abcd","APP","DBMS_CLOUD_AI.GENERATE","2","begin ...",List.of());
        for(var locale:List.of(Locale.KOREAN,Locale.ENGLISH,Locale.SIMPLIFIED_CHINESE,Locale.JAPANESE)) for(var source:Source.values()) {
            String html=render(source,new Page(List.of(row),2,true,Instant.now(),null),null,locale,Match.generate);
            assertThat(html).contains("name=\"match\"", "value=\"generate\" selected=\"selected\"", "match=generate", "page=1", "page=3", "refresh=true", "DBMS_CLOUD_AI.GENERATE</td>")
                    .doesNotContain("??", "th:");
        }
    }
    @Test void allSourceEmptyRowsHaveCorrectColspanAndExplainBindLimitations() {
        assertThat(render(Source.cache,page(null),null,Locale.KOREAN)).contains("colspan=\"6\"", "바인드로 전달한 질문", "문자열·주석");
        assertThat(render(Source.awr,page(null),null,Locale.KOREAN)).contains("colspan=\"5\"");
        assertThat(render(Source.audit,page(null),null,Locale.KOREAN)).contains("colspan=\"5\"");
    }
}
