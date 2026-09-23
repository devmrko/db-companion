package com.dbcompanion;

import com.dbcompanion.model.DatabaseInfo;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class OntologyTemplateTest {
    @Test void workflowLabelsSeparateSamplesFromSavedRdf(){
        var messages=com.dbcompanion.common.i18n.UiMessages.source();
        for(String language:List.of("ko","en","zh-CN","ja")){
            var locale=Locale.forLanguageTag(language);
            assertThat(messages.getMessage("ontology.wizard.title",null,locale)).isNotBlank();
            assertThat(messages.getMessage("ontology.context.help",null,locale)).contains("RDF");
            assertThat(messages.getMessage("ontology.ai",null,locale)).isNotEqualTo(messages.getMessage("ontology.wizard.title",null,locale));
            for(String key:List.of("valueMeaning","labelColumn","semanticsTitle","semanticsHelp","labelInvalid","usageGuidance","editDefinition","genericHelp"))assertThat(messages.getMessage("ontology.wizard."+key,null,locale)).isNotBlank();
        }
    }
    @Test void erdReadabilityControlsAreTranslated() throws Exception {
        String html=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/fragments/ontology-erd.html"));
        assertThat(html).contains("data-erd-focus", "data-erd-actual", "data-erd-zoom", "#{ontology.erd.focus}", "#{ontology.erd.actual}", "#{ontology.erd.zoomLevel}");
    }
    @Test void relationshipActionAndCompletionLabelsAreTranslated() throws Exception {
        var messages=com.dbcompanion.common.i18n.UiMessages.source();
        for(String language:List.of("ko","en","zh-CN","ja"))for(String key:List.of("completed","reused","sourceColumn","targetColumn"))
            assertThat(messages.getMessage("ontology.relationships."+key,new Object[]{"2026-09-20"},Locale.forLanguageTag(language))).isNotBlank().doesNotContain("??");
        String html=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/fragments/ontology-relationships.html"));
        assertThat(html).contains("id=\"ontology-relationships\"","tabindex=\"-1\"","data-rel-result role=\"status\"","data-rel-in","data-rel-out");
    }
    @Test void importDialogIsAccessibleAndDoesNotStartByItself() throws Exception {
        String html=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/ontology.html"));
        assertThat(html).contains("data-on-import-all", "data-on-import-dialog aria-labelledby=\"ontology-import-title\"",
                "data-import-start", "data-import-stop hidden", "data-import-close", "aria-live=\"polite\"",
                "data-import-progress", "data-import-rows").doesNotContain("onclick=", "onload=");
    }
    @Test void fourLanguagesRenderNoAutomaticInstallationAndEscapedSchema(){var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(String language:List.of("ko","en","zh-CN","ja")){var c=new Context(Locale.forLanguageTag(language));c.setVariable("activePage","ontology");c.setVariable("info",new DatabaseInfo("APP","APP","LOW","DB"));c.setVariable("schemas",List.of("<schema>"));c.setVariable("selectedSchema","<schema>");c.setVariable("languageReturn","/ontology");c.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","render-only"));String html=engine.process("ontology",c);assertThat(html).contains("&lt;schema&gt;","data-on-install hidden","data-on-ready hidden","data-on-consent","data-on-tab=\"rdf\"","data-on-tab=\"history\"","data-csrf-header=\"X-CSRF-TOKEN\"").doesNotContain("??ontology.","th:","data-schema=\"<schema>");assertThat(html.split("value=\"/ontology\"",-1)).hasSize(4);}
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/ontology")).isEqualTo("/ontology");assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/ontology/install")).isEqualTo("/login");
    }
}
