package com.dbcompanion;

import com.dbcompanion.model.DatabaseInfo;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class OrdsManagementTemplateTest {
    @Test void menuAndHierarchyRenderInAllLanguagesWithEscapingAndSafeReturn(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(String language:List.of("ko","en","zh-CN","ja")){
            var context=new Context(Locale.forLanguageTag(language));context.setVariable("activePage","ords");context.setVariable("info",new DatabaseInfo("APP","APP","LOW","DB"));context.setVariable("schemas",List.of("<script>"));context.setVariable("selectedSchema","<script>");context.setVariable("languageReturn","/db/ords");
            var html=engine.process("ords",context);
            assertThat(html).contains("&lt;script&gt;","data-ords-csrf","data-ords-modules","data-ords-templates","data-ords-handlers","data-ords-preview","data-ords-confirm","data-ords-apply","data-sql-help-for=\"ords\"").doesNotContain("??ords.","th:","data-schema=\"<script>");
            assertThat(html.split("value=\"/db/ords\"",-1)).hasSize(4);
            assertThat(html).containsPattern("<a[^>]*href=\"/db/ords\"[^>]*aria-current=\"page\"[^>]*>");
        }
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/db/ords")).isEqualTo("/db/ords");
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/db/ords/apply")).isEqualTo("/login");
    }
}
