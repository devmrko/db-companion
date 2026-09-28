package com.dbcompanion;
import java.nio.file.*;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import static org.assertj.core.api.Assertions.*;

/** Render actual form labels: th:text on a parent must not erase its controls. */
class ProblemQuestionTemplateRenderTest {
    @Test void glossaryEditorKeepsItsInputsAndSelectAiKeepsItsSelectionFragment() throws Exception {
        assertThat(renderLabels("business-glossary.html")).contains("data-glossary-term","data-glossary-definition","data-glossary-aliases","data-glossary-enabled","data-glossary-save-consent");
    }
    @Test void translatedLabelsRetainRequiredFormInputsAfterThymeleafRendering() throws Exception {
        assertThat(renderLabels("ai-problems.html")).contains("data-problem-description","data-problem-expected","data-problem-expected-sql","data-attempt-metadata");
    }
    @Test void testResultProblemDialogRetainsItsRequiredInputsAfterThymeleafRendering() throws Exception {
        assertThat(renderLabels("ai-test.html")).contains("data-test-problem-description","data-test-problem-expected","data-test-problem-sql");
    }
    @Test void englishProblemAndComparisonControlsRenderWithoutKoreanVisibleLabels() throws Exception {
        var resolver=new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        var context=new Context(Locale.ENGLISH);context.setVariable("info",Map.of("username","APP"));context.setVariable("_csrf",Map.of("token","t","headerName","X"));
        String problems=engine.process("ai-problems",context);
        String test=engine.process("ai-test",context);
        String dictionary=engine.process("business-glossary",context);
        assertThat(dictionary).contains("Business dictionary","data-glossary-manager","data-glossary-definition","data-glossary-setup-consent").doesNotContain("??businessGlossary");
        assertThat(test).contains("data-business-glossary","data-glossary-results","Use business dictionary");
        assertThat(problems).contains("Batch SQL generation","Save selected results","Saved problem questions").doesNotContain("등록 질문 일괄 SQL 생성","선택 결과 저장","저장된 문제 질문");
        assertThat(test).contains("Profile A/B SQL comparison","Review transmission plan","Confirm A/B SQL generation","Generate Profile A","Confirm A/B SHOWPROMPT","This adds two SHOWPROMPT calls","Confirm AI comparison","Request AI comparison")
                .doesNotContain("프로필 A/B SQL 비교","전송 계획 보기","A/B SQL 생성 확인","프로필 A 생성","A/B SHOWPROMPT 확인","추가 AI 비교 호출 1회입니다.");
        assertThat(problems).contains("data-problem-question","data-attempt-input","data-problem-export-preview");
    }
    private String renderLabels(String template) throws Exception {
        String page=Files.readString(Path.of("src/main/resources/templates/"+template));
        var labels=Pattern.compile("<label\\b[^>]*>[\\s\\S]*?</label>").matcher(page);
        var fragment=new StringBuilder("<form xmlns:th='http://www.thymeleaf.org'>");
        while(labels.find())fragment.append(labels.group());fragment.append("</form>");
        var engine=new SpringTemplateEngine();var resolver=new StringTemplateResolver();resolver.setTemplateMode("HTML");engine.setTemplateResolver(resolver);
        return engine.process(fragment.toString(),new Context(Locale.KOREAN));
    }
}
