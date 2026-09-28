package com.dbcompanion;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class SelectAiSetupTemplateTest {
    @Test void setupGuideUsesPlaceholdersAndOfficialLinksInEveryLanguage() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source()); engine.setTemplateResolver(resolver);
        for (var locale : java.util.List.of(Locale.KOREAN, Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.JAPANESE)) {
            String html = engine.process("fragments/select-ai-setup", new Context(locale));
            assertThat(html).contains("OCI$RESOURCE_PRINCIPAL", "ENABLE_RESOURCE_PRINCIPAL", "&lt;PRIVATE_KEY_PEM&gt;",
                    "resource-principal.html", "apisigningkey.htm", "create-ai-profile.html",
                    "class=\"app-card app-setup-guide\"", "class=\"app-disclosure\"", "class=\"app-disclosure-body\"",
                    "class=\"app-preview-source\" tabindex=\"0\"")
                    .doesNotContain("??", "th:", "app-dds-help");
        }
    }
}
