package com.dbcompanion;

import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.controller.LanguageController;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class CallableAiTemplateTest {
    @Test void allLanguagesShowIndependentOptionsAndAdminOnlyPrivileges(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(String language:List.of("ko","en","zh-CN","ja")){
            var c=new Context(Locale.forLanguageTag(language));c.setVariable("activePage","functions");c.setVariable("info",new DatabaseInfo("APP","OTHER","LOW","DB"));
            c.setVariable("languageReturn","/db/functions/ai-query");c.setVariable("callableAdmin",false);
            c.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","test-token"));
            var html=engine.process("callable-ai",c);assertThat(html).contains("data-callable","data-ca-glossary","data-ca-ontology","data-ca-consent","callable-ai.css","value=\"CONTEXT\"")
                .doesNotContain("??callable.","data-ca-access-user","th:");
            c.setVariable("callableAdmin",true);assertThat(engine.process("callable-ai",c)).contains("data-ca-access-user","data-ca-access-grant").doesNotContain("??callable.");
        }
        assertThat(LanguageController.safeReturn("/db/functions/ai-query")).isEqualTo("/db/functions/ai-query");
        assertThat(LanguageController.safeReturn("/db/functions/ai-query/install")).isEqualTo("/login");
    }
}
