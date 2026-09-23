package com.dbcompanion;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class ProfileHistoryTemplateTest {
    @Test void actualHeadingEscapesProfileNamesWithoutGlobalHistoryControls() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source()); engine.setTemplateResolver(resolver);
        var context = new Context(java.util.Locale.KOREAN); context.setVariable("profileName", "\"><script>unsafe</script>");
        String html = engine.process("fragments/profile-history", Set.of("//header"), context);
        assertThat(html).contains("&lt;script&gt;", "변경 이력", "닫기");
        assertThat(html).doesNotContain("<script>", "th:text=");
        String install = engine.process("fragments/profile-history", Set.of("//button[@data-ph-install]"), context);
        assertThat(install).contains("data-ph-install", "hidden", "공통 이력 준비");
    }
}
