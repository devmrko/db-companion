package com.dbcompanion;

import com.dbcompanion.model.DatabaseInfo;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class VpdManagementTemplateTest {
    @Test void allLanguagesRenderNavigationConsentFieldsAndEscapeSchema(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(var entry:Map.of("ko","VPD 정책","en","VPD policies","ja","VPD ポリシー","zh-CN","VPD 策略").entrySet()){
            var context=new Context(Locale.forLanguageTag(entry.getKey()));context.setVariable("activePage","vpd");context.setVariable("info",new DatabaseInfo("APP","APP","TEST","DB"));
            context.setVariable("schemas",List.of("<script>"));context.setVariable("selectedSchema","<script>");context.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","test-only"));
            var html=engine.process("vpd-management",context);assertThat(html).contains(entry.getValue(),"value=\"/db/vpd\"","data-vpd-consent","data-vpd-gap-consent","data-vpd-confirm-target","data-vpd-recovery","/js/vpd-management.mjs","&lt;script&gt;","DROP_POLICY","ADD_POLICY").doesNotContain("??vpd.","th:","data-schema=\"<script>");
            assertThat(html).contains("data-vpd-statement value=\"SELECT\"","data-vpd-statement value=\"INSERT\"");
            assertThat(html).contains("data-vpd-kind","data-vpd-access","data-vpd-search","data-vpd-object-count","data-vpd-object-access","value=\"GRANTED\"","value=\"VIEW\"");
            assertThat(html).contains("data-vpd-source-dialog","data-vpd-source-code","data-vpd-source-close","data-vpd-policy-function","data-source-url=\"/db/functions/detail\"");
            assertThat(html).contains("/css/management-workbench.css", "app-management-workbench").doesNotContain("??workbench.");
            assertThat(html.indexOf("data-vpd-policy-function")).isLessThan(html.indexOf("data-vpd-form>"));
        }
    }
    @Test void realCsrfFilterRejectsMutationWithoutToken() throws Exception {
        var repository=new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository();var filter=new org.springframework.security.web.csrf.CsrfFilter(repository);
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/db/vpd/execute");var response=new org.springframework.mock.web.MockHttpServletResponse();var called=new java.util.concurrent.atomic.AtomicBoolean();
        filter.doFilter(request,response,(req,res)->called.set(true));assertThat(response.getStatus()).isEqualTo(403);assertThat(called).isFalse();
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/db/vpd")).isEqualTo("/db/vpd");
    }
}
