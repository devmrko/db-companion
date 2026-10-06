package com.dbcompanion;

import com.dbcompanion.model.AiProfileAttribute;
import com.dbcompanion.service.DatabaseService.ProfilePage;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class ProfileEditTemplateTest {
    private String render(boolean owner) {
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());engine.setTemplateResolver(resolver);
        var context=new Context(java.util.Locale.KOREAN);context.setVariable("profileName","P");context.setVariable("profileEditable",owner);
        context.setVariable("editableAttributes",List.of("additional_instructions","max_tokens"));
        context.setVariable("page",new ProfilePage(List.of(),AiProfileAttribute.withTokenLimit(List.of(new AiProfileAttribute("additional_instructions","<script>unsafe</script>\n긴 내용")))));
        return engine.process("ai-profiles",Set.of("//table"),context);
    }
    @Test void ownerSeesBoundedEditActionAndEscapedFullValue() {
        assertThat(render(true)).contains("data-edit-profile-attribute=\"additional_instructions\"","additional_instructions 편집","&lt;script&gt;","긴 내용")
                .doesNotContain("<script>unsafe", "th:text=");
    }
    @Test void crossSchemaAdminHasNoEditAction() {
        assertThat(render(false)).contains("긴 내용", "data-profile-attribute-name=\"additional_instructions\"").doesNotContain("data-edit-profile-attribute");
    }
    @Test void optionalTokenLimitIsVisibleAndEditableOnlyForTheOwner(){
        assertThat(render(true)).contains("data-edit-profile-attribute=\"max_tokens\"","기본값 (미설정)");
        assertThat(render(false)).contains("max_tokens","기본값 (미설정)").doesNotContain("data-edit-profile-attribute");
        var existing=List.of(new AiProfileAttribute("max_tokens","8192"));
        assertThat(AiProfileAttribute.withTokenLimit(existing)).isEqualTo(existing);
    }
}
