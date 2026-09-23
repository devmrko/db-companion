package com.dbcompanion;

import com.dbcompanion.model.DatabaseInfo;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class FunctionTemplateTest {
    @Test void fourLanguageFunctionPagesEscapeNamesAndHaveNoWriteOrRunControls(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,java.util.Map<String,Object> p){return "";}
        });
        var titles=java.util.Map.of("ko","함수","en","Functions","zh-CN","函数","ja","関数");
        titles.forEach((language,title)->{
            var context=new Context(java.util.Locale.forLanguageTag(language));context.setVariable("activePage","functions");
            context.setVariable("info",new DatabaseInfo("APP","APP","LOW","DB"));context.setVariable("schemas",List.of("APP"));context.setVariable("selectedSchema","APP");
            context.setVariable("routineName","\"APP\".\"<script>\"");
            context.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","template-render-only"));
            String html=engine.process("functions",context);
            assertThat(html).contains(title,"data-functions","data-functions-filter","data-functions-detail","value=\"/db/functions\"","/js/functions.mjs","&lt;script&gt;","#code","data-ae-consent","data-function-explain")
                    .doesNotContain("th:","??functions.","<script>\"","data-execute","data-save","contenteditable");
            context.setVariable("loadError","ORA-00942 <view>");
            assertThat(engine.process("functions",context)).contains("ORA-00942 &lt;view&gt;").doesNotContain("data-functions-detail");
        });
    }
}
