package com.dbcompanion;

import com.dbcompanion.common.db.AgentObjectEditPolicy;
import com.dbcompanion.model.AgentCatalog.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class AgentObjectTemplateTest {
    private SpringTemplateEngine engine() {
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());engine.setTemplateResolver(resolver);return engine;
    }
    private Context context() {
        var context=new Context(java.util.Locale.KOREAN);context.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","renderer-token"));return context;
    }
    private String component(String label,boolean editable,List<String> attributes) {
        var context=context();String name="\"><script>bad</script>";
        context.setVariable("item",new Component(name,new Item("1",name,"d","ENABLED","c","m"),attributes.stream().map(a->new Attribute(name,a,"<script>value</script>","m")).toList()));
        context.setVariable("label",label);context.setVariable("objectEditable",editable);
        var allowed=new HashMap<String,Set<String>>();AgentObjectEditPolicy.EDITABLE.forEach((kind,set)->allowed.put(kind.name(),set));context.setVariable("editableObjectAttributes",allowed);
        return engine().process("fragments/agent",Set.of("component"),context);
    }
    @Test void everyObjectRendersItsOwnControlsAndImmutableAttributesStayReadOnly() {
        for(String label:List.of("Agent","Supervisor Agent","Task","Tool")) {
            String attr=label.contains("Agent")?"role":"instruction";
            String html=component(label,true,List.of(attr,"supervisor","unknown"));
            assertThat(html).contains("data-open-object-history", "data-edit-object-attribute=\""+attr+"\"", "&lt;script&gt;")
                    .doesNotContain("data-edit-object-attribute=\"supervisor\"","data-edit-object-attribute=\"unknown\"","<script>bad", "<script>value");
            assertThat(component(label,false,List.of(attr))).doesNotContain("data-edit-object-attribute","data-open-object-history");
        }
    }
    @Test void allEditorHooksSurviveRealThymeleafRendering() {
        String html=engine().process("fragments/object-editor",Set.of("//form"),context());
        for(String hook:List.of("form","csrf","object","message","fields","label","help","control","raw","value","save"))assertThat(html).contains("data-aoe-"+hook);
        assertThat(html).contains("data-csrf-header=\"X-CSRF-TOKEN\"","disabled","같은 객체를 사용하는 다른 Team").doesNotContain("data-th-");
    }
    @Test void allHistoryHooksSurviveRealThymeleafRendering() {
        String html=engine().process("fragments/object-history",Set.of("//header","//div[@class='app-history-content']"),context());
        for(String hook:List.of("title","close","csrf","refresh","install","message","comparison","entries","prev","page","next"))assertThat(html).contains("data-aoh-"+hook);
        assertThat(html).contains("공통 이력 준비", "hidden").doesNotContain("data-th-","감사 ON");
    }
}
