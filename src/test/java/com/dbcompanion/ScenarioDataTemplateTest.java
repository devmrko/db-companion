package com.dbcompanion;

import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.service.ScenarioDataService;
import com.dbcompanion.service.WalletCatalogService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class ScenarioDataTemplateTest {
    @Test void rendersLocalizedFormOutputAndAdminGuidanceWithoutLoginOrDatabase() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext context, String base, Map<String,Object> parameters) { return ""; }
        });
        for (String language : List.of("ko", "en", "zh-CN", "ja")) {
            var context = new Context(Locale.forLanguageTag(language));
            context.setVariable("activePage", "scenario-data");
            context.setVariable("languageReturn", "/db/scenario-data");
            context.setVariable("info", new DatabaseInfo("APP", "APP", "LOW", "DB"));
            context.setVariable("presets", ScenarioDataService.PRESETS);
            context.setVariable("walletOptions", List.of(new WalletCatalogService.Option("id", "<test>")));
            context.setVariable("_csrf", new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "fixture"));
            String html = engine.process("scenario-data", context);
            assertThat(html).contains("&lt;test&gt;", "scenario-config.json", "scenario-prompt.md", "CREATE SESSION", "QUOTA 100M", "data-scenario-generate", "data-copy=\"config\"", "data-download=\"prompt\"")
                    .doesNotContain("??", "th:", "name=\"password\"");
            assertThat(html).containsPattern("<a[^>]*href=\"/db/scenario-data\"[^>]*aria-current=\"page\"");
        }
    }
}
