package com.dbcompanion;

import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.controller.LanguageController;
import com.dbcompanion.model.DatabaseInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

/** Combined feature templates, not a simulated authenticated database session. */
class WorkbenchIntegrationTemplateTest {
    private SpringTemplateEngine engine() {
        var resolver=new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}
        });
        return engine;
    }
    private Context context(String language,String page) {
        var c=new Context(Locale.forLanguageTag(language));
        c.setVariable("activePage",page);c.setVariable("info",new DatabaseInfo("APP","APP","LOW","DB"));
        c.setVariable("executionTimeoutSeconds",600);
        c.setVariable("schemas",List.of("APP"));c.setVariable("selectedSchema","APP");
        c.setVariable("languageReturn",page.equals("ai-problems")?"/ai-test/problems":"/"+page);
        c.setVariable("_csrf",new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","synthetic-render-only"));
        return c;
    }
    @Test void combinedTestPageKeepsSetupHelpSaveComparisonAndConfirmationControls() {
        for(String language:List.of("ko","en","ja","zh-CN")){
            String html=engine().process("ai-test",context(language,"ai-test"));
            assertThat(html).contains("data-sql-help-dialog","OCI$RESOURCE_PRINCIPAL","data-test-comparison-left",
                    "data-test-comparison-ai-consent","data-test-problem-description","data-test-condition-answer",
                    "data-test-execution-consent","href=\"/ai-test/problems\"","data-execution-timeout-seconds=\"600\"","data-test-execution-timeout")
                    .doesNotContain("??aitest.","??problemQuestion.","??sqlHelp.","th:");
        }
    }
    @Test void executionReviewDisplaysConfiguredBudgetInsteadOfHardcodedLimit(){
        String html=engine().process("ai-test",context("ko","ai-test"));
        assertThat(html).contains("SQL 실행 제한: 600초").doesNotContain("SQL 실행 제한은 120초");
    }
    @Test void problemPageKeepsExplicitBatchAndSaveControlsWithCommonHelp() {
        for(String language:List.of("ko","en","ja","zh-CN")){
            String html=engine().process("ai-problems",context(language,"ai-problems"));
            assertThat(html).contains("data-sql-help-dialog","data-problem-question","data-problem-description",
                    "data-problem-expected","data-batch-consent","data-batch-save-consent","data-problem-export-preview")
                    .doesNotContain("??problemQuestion.","??aitest.","??sqlHelp.","th:");
        }
    }
    @Test void languageChangeReturnsToNewPageButNotItsMutationEndpoints() {
        assertThat(LanguageController.safeReturn("/ai-test/problems")).isEqualTo("/ai-test/problems");
        for(String path:List.of("/ai-test/problems/save","/ai-test/problems/delete","/ai-test/problems?delete=true"))
            assertThat(LanguageController.safeReturn(path)).isEqualTo("/login");
    }
    @Test void screenSqlHelpRendersExplicitContextWithoutGlobalOpener() {
        for(String language:List.of("ko","en","ja","zh-CN")) {
            String html=engine().process("ontology",context(language,"ontology"));
            assertThat(html).contains("data-sql-help-for=\"ontology\"", "data-sql-help-operation=\"current\"",
                    "id=\"sql-help-operation\"", "data-sql-help-source", "data-sql-help-copy-verify")
                    .doesNotContain("data-sql-help-open", "??sqlHelp.", "th:");
            String test=engine().process("ai-test",context(language,"ai-test"));
            assertThat(test).contains("data-sql-help-for=\"ai-test\"", "data-sql-help-operation=\"showsql\"");
        }
    }
    @Test void ontologyAndDictionaryHaveSeparateControlsInEveryLanguage() {
        for(String language:List.of("ko","en","ja","zh-CN")) {
            String html=engine().process("ontology",context(language,"ontology"));
            assertThat(html).contains("app-preview-dialog app-sql-help-dialog", "data-on-list", "data-on-save")
                    .doesNotContain("data-on-glossary", "data-on-text-install", "??ontology.", "??sqlHelp.", "th:");
            String dictionary=engine().process("business-glossary",context(language,"business-glossary"));
            assertThat(dictionary).contains("data-glossary-text-status", "data-glossary-text-setup hidden disabled")
                    .doesNotContain("??businessGlossary.", "th:");
        }
    }
    @Test void mergedMessageBundlesHaveNoDuplicateKeysAndSameFeatureKeySets() throws Exception {
        var reference=new HashSet<String>();
        for(String file:List.of("messages.properties","messages_ko.properties","messages_en.properties","messages_ja.properties","messages_zh_CN.properties")){
            var keys=new HashSet<String>();var features=new HashSet<String>();
            for(String line:Files.readAllLines(Path.of("src/main/resources/i18n",file))){
                if(line.isBlank()||line.startsWith("#")||line.startsWith("!"))continue;
                int end=line.indexOf('=');if(end<0)continue;String key=line.substring(0,end);
                assertThat(keys.add(key)).as("duplicate %s in %s",key,file).isTrue();
                if(key.startsWith("aitest.")||key.startsWith("problemQuestion.")||key.startsWith("sqlHelp.")||key.startsWith("preflight."))features.add(key);
            }
            if(reference.isEmpty())reference.addAll(features);else assertThat(features).as(file).isEqualTo(reference);
        }
    }
}
