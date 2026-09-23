package com.dbcompanion;

import com.dbcompanion.model.AgentCatalog.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual Thymeleaf fragment renderer; no mocked servlet, login, or database. */
class AgentTemplateTest {
    private String render(Component component) {
        return render(component,"Tool",false);
    }
    private String render(Component component,String label,boolean editable) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setTemplateResolver(resolver);
        var context = new Context(java.util.Locale.KOREAN);
        context.setVariable("item", component);
        context.setVariable("label", label);
        context.setVariable("teamEditable",editable);
        context.setVariable("editableTeamAttributes",com.dbcompanion.common.db.TeamEditPolicy.EDITABLE);
        return engine.process("fragments/agent", Set.of("component"), context);
    }

    @Test void rendersFullAttributeValueAsText() {
        var item = new Item("1", "TOOL", "설명", "ENABLED", "created", "modified");
        var html = render(new Component("TOOL", item,
                List.of(new Attribute("1", "instruction", "<script>alert(1)</script>\n긴 값", "modified"))));
        assertThat(html).contains("instruction", "&lt;script&gt;", "긴 값", "ENABLED");
        assertThat(html).doesNotContain("<script>", "th:text=");
    }

    @Test void missingReferenceIsVisibleWithoutNullDereference() {
        assertThat(render(new Component("MISSING_TOOL", null, List.of())))
                .contains("MISSING_TOOL", "참조된 항목이 없거나 조회 권한이 없습니다.");
    }
    @Test void teamOwnerGetsOnlySupportedAttributeEditsAndNoNestedEdits() {
        var component=new Component("TEAM",new Item("1","TEAM","description","ENABLED","created","modified"),List.of(
                new Attribute("TEAM","agents","<script>unsafe</script>","modified"),new Attribute("TEAM","unknown","raw","modified")));
        assertThat(render(component,"Team",true)).contains("data-edit-team-attribute=\"agents\"","data-team-attribute-name=\"agents\"","&lt;script&gt;")
                .doesNotContain("data-edit-team-attribute=\"unknown\"","<script>unsafe");
        assertThat(render(component,"Team",false)).doesNotContain("data-edit-team-attribute");
        assertThat(render(component,"Agent",true)).doesNotContain("data-edit-team-attribute");
    }
}
