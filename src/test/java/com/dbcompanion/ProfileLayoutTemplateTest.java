package com.dbcompanion;

import com.dbcompanion.model.AiProfile;
import com.dbcompanion.model.AiProfileAttribute;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.service.DatabaseService.ProfilePage;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class ProfileLayoutTemplateTest {
    static final String NAME = "APP_" + "LONG_PROFILE_".repeat(6);
    static String render(String language, boolean detail, boolean owner) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c, String base, java.util.Map<String, Object> params) { return ""; }
        });
        String longText = "합성 설명 · Synthetic · テスト · 测试 " + "LONG_VALUE_".repeat(70) + "<script>not-code</script>";
        var attributes = List.of(new AiProfileAttribute("additional_instructions", longText),
                new AiProfileAttribute("object_list", "[{\"owner\":\"APP\",\"name\":\"LONG_OBJECT_NAME_FOR_LAYOUT_TEST\"}]"),
                new AiProfileAttribute("comments", "true"), new AiProfileAttribute("max_tokens", "2048"),
                new AiProfileAttribute("provider", "oci"), new AiProfileAttribute("model", "synthetic-model"),
                new AiProfileAttribute("credential_name", "SYNTHETIC_CREDENTIAL"));
        var context = new Context(Locale.forLanguageTag(language));
        context.setVariable("_csrf", new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "synthetic-not-valid"));
        context.setVariable("info", new DatabaseInfo(owner ? "APP" : "READER", "APP", "LOW", "SYNTHETIC"));
        context.setVariable("schemas", List.of("APP")); context.setVariable("selectedSchema", "APP");
        context.setVariable("activePage", "profiles"); context.setVariable("profileName", detail ? NAME : null);
        context.setVariable("profileEditable", owner);
        context.setVariable("editableAttributes", attributes.stream().map(AiProfileAttribute::name).toList());
        context.setVariable("page", new ProfilePage(List.of(new AiProfile(NAME, "ENABLED", longText, "2026-01-02", "42", "2026-01-01")), detail ? attributes : List.of()));
        return engine.process("ai-profiles", context);
    }
    @ParameterizedTest @ValueSource(strings = {"ko", "en", "ja", "zh-CN"})
    void detailRetainsFullEscapedContentAndControls(String language) {
        assertThat(render(language, true, true)).contains("app-shell app-profile-detail-page", "app-profile-heading-actions",
                NAME, "LONG_VALUE_".repeat(70), "&lt;script&gt;not-code&lt;/script&gt;", "app-card table-responsive",
                "data-profile-editor", "data-ph-dialog", "data-profile-preflight", "data-edit-profile-attribute=\"object_list\"")
                .doesNotContain("<script>not-code</script>", "??ui.", "th:");
    }
    @Test void listDoesNotAcquireDetailLayoutRules() {
        assertThat(render("ko", false, true)).contains("data-list-row").doesNotContain("app-profile-detail-page", "data-profile-editor", "data-ph-dialog");
    }
    @Test void crossSchemaDetailRetainsReadOnlyBoundary() {
        assertThat(render("ko", true, false)).contains("app-profile-detail-page", NAME, "data-ph-dialog")
                .doesNotContain("data-edit-profile-attribute", "data-profile-editor", "data-ai-create=\"PROFILE\"");
    }
}
