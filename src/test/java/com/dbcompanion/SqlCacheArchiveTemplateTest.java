package com.dbcompanion;

import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.controller.LanguageController;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class SqlCacheArchiveTemplateTest {
    @Test void allLanguagesRenderArchiveNavigationAndSqlConfirmation(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(String language:List.of("ko","en","zh-CN","ja")){
            var c=new Context(Locale.forLanguageTag(language));c.setVariable("activePage","executions");c.setVariable("info",new DatabaseInfo("APP","OTHER","LOW","DB"));
            c.setVariable("languageReturn","/ai-executions/sql/archive");c.setVariable("today",LocalDate.of(2026,1,10));c.setVariable("from",LocalDate.of(2026,1,4));
            c.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","test-token"));
            var html=engine.process("sql-cache-archive",c);
            assertThat(html).contains("data-sql-archive","data-ar-consent","data-ar-op=\"INSTALL\"","data-ar-op=\"PAUSE\"","data-ar-op=\"RUN\"","sql-cache-archive.css","DBC_SQL_CACHE_ARCHIVE")
                    .doesNotContain("??archive.","??sqlh.archive","th:");
            assertThat(html).containsPattern("<a[^>]*href=\"/ai-executions/sql/archive\"[^>]*aria-current=\"page\"[^>]*>");
            assertThat(html).doesNotContain("data-ar-access-user");
            c.setVariable("archiveAdmin",true);var admin=engine.process("sql-cache-archive",c);
            assertThat(admin).contains("data-ar-access-user","data-ar-access-check","data-ar-access-grant","CREATE JOB").doesNotContain("??archive.");
        }
        assertThat(LanguageController.safeReturn("/ai-executions/sql/archive")).isEqualTo("/ai-executions/sql/archive");
        assertThat(LanguageController.safeReturn("/ai-executions/sql/archive/apply")).isEqualTo("/login");
    }
}
