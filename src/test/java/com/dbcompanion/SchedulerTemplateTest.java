package com.dbcompanion;

import com.dbcompanion.model.DatabaseInfo;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class SchedulerTemplateTest {
    @Test void fourLanguagesRenderEscapedSchemaNavigationTabsAndReadOnlyHelp(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(String language:List.of("ko","en","zh-CN","ja")){
            var context=new Context(Locale.forLanguageTag(language));context.setVariable("activePage","scheduler");context.setVariable("info",new DatabaseInfo("APP","APP","LOW","DB"));context.setVariable("schemas",List.of("<script>"));context.setVariable("selectedSchema","<script>");context.setVariable("languageReturn","/db/scheduler");
            var html=engine.process("scheduler",context);
            assertThat(html).contains("&lt;script&gt;","data-scheduler","data-scheduler-tab=\"settings\"","data-scheduler-tab=\"code\"","data-scheduler-tab=\"history\"","user_scheduler_jobs","user_scheduler_job_run_details").doesNotContain("??scheduler.","th:","data-schema=\"<script>","RUN_JOB","STOP_JOB");
            assertThat(html.split("value=\"/db/scheduler\"",-1)).hasSize(4);
            assertThat(html).containsPattern("<a[^>]*href=\"/db/scheduler\"[^>]*aria-current=\"page\"[^>]*>");
        }
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/db/scheduler")).isEqualTo("/db/scheduler");
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/db/scheduler/run")).isEqualTo("/login");
    }
}
