package com.dbcompanion;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class TeamEditTemplateTest {
    private SpringTemplateEngine engine() {
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());engine.setTemplateResolver(resolver);return engine;
    }
    @Test void historyTitleEscapesNameAndDoesNotExposeGlobalAuditControls() {
        var context=new Context(java.util.Locale.KOREAN);context.setVariable("teamName","\"><script>unsafe</script>");
        String html=engine().process("fragments/team-history",Set.of("//header"),context);
        assertThat(html).contains("&lt;script&gt;","변경 이력","닫기").doesNotContain("<script>","th:text=","감사 ON");
    }
    @Test void editorStartsDisabledAndKeepsRawJsonAndExplicitSave() {
        String html=engine().process("fragments/team-editor",Set.of("//fieldset"),new Context(java.util.Locale.KOREAN));
        assertThat(html).contains("fieldset", "disabled", "JSON 직접 편집", "textarea", "저장", "data-te-save").doesNotContain("th:");
    }
    @Test void historyJavaScriptHooksSurviveTheActualThymeleafRenderer() throws Exception {
        var context=new Context(java.util.Locale.KOREAN);
        context.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","renderer-value"));
        String html=engine().process("fragments/team-history",Set.of("//div[@class='app-history-content']"),context);
        for(String hook:java.util.List.of("csrf","refresh","install","message","comparison","entries","prev","page","next"))
            assertThat(html).contains("data-tmh-"+hook);
        assertThat(html).contains("data-csrf-header=\"X-CSRF-TOKEN\"").doesNotContain("data-th-");
        String script=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/js/team-history.mjs"));
        assertThat(script).contains("[data-tmh-${name}]").doesNotContain("[data-th-");
    }
}
