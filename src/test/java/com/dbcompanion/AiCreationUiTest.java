package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.service.DatabaseService.ProfilePage;
import com.dbcompanion.controller.AiCreationController.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class AiCreationUiTest {
    @LocalServerPort int port;@Autowired JsonMapper json;
    @Test void realHttpRequiresLoginAndCsrf() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:List.of("/ai-create/form?schema=APP&kind=PROFILE","/ai-feedback/edit?schema=APP&profile=P","/ai-agents/objects","/ai-agents/object?schema=APP&kind=AGENT&name=A")){
            var result=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(result.statusCode()).isEqualTo(302);
        }
        for(String path:List.of("install","preview","create","copy")){
            var result=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ai-create/"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(result.statusCode()).as(path).isEqualTo(403);
        }
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ai-feedback/edit")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(403);
    }
    @Test void consentAndUnknownFieldsCannotBeSilentlyCoerced(){assertThatThrownBy(()->json.readValue("{\"token\":\"t\",\"confirmed\":true}",Create.class)).isInstanceOf(tools.jackson.core.JacksonException.class);assertThatThrownBy(()->json.readValue("{\"token\":\"t\",\"confirmed\":true,\"consent\":true,\"sql\":\"ignored\"}",Create.class)).isInstanceOf(tools.jackson.core.JacksonException.class);}
    @Test void fourLanguagesRenderOwnerActionsAndEscapedNames(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(String language:List.of("ko","en","zh-CN","ja")){
            var c=new Context(Locale.forLanguageTag(language));c.setVariable("activePage","profiles");c.setVariable("info",new DatabaseInfo("APP","APP","LOW","DB"));c.setVariable("schemas",List.of("APP"));c.setVariable("selectedSchema","APP");c.setVariable("targetSchema","APP");c.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","render-only"));
            c.setVariable("profileName",null);c.setVariable("page",new ProfilePage(List.of(),List.of()));c.setVariable("teams",List.of());c.setVariable("objects",List.of());c.setVariable("objectKind","AGENT");c.setVariable("objectName","<source>");c.setVariable("objectEditable",true);c.setVariable("editableObjectAttributes",Map.of("AGENT",Set.of("role")));
            c.setVariable("component",new AgentCatalog.Component("<source>",new AgentCatalog.Item("1","<source>","<text>","DISABLED","now","now"),List.of(new AgentCatalog.Attribute("<source>","role","<script>bad</script>","now"))));
            for(String template:List.of("ai-profiles","ai-agents","ai-agent-objects","ai-agent-object")){
                String html=engine.process(template,c);assertThat(html).as(template+language).contains("data-ai-creation","data-ac-confirm","data-ac-consent","data-ac-create disabled","/js/ai-creation.mjs","id=\"ai-creation-copy-help\"","aria-describedby=\"ai-creation-copy-help\"")
                        .doesNotContain("??creation.","th:","<script>bad","100건","100 entries","100 条","100 件");
            }
            String object=engine.process("ai-agent-object",c);assertThat(object).contains("&lt;source&gt;","&lt;script&gt;bad&lt;/script&gt;","data-edit-object-attribute=\"role\"");
            c.setVariable("profile","P");c.setVariable("detailMode",false);c.setVariable("search","");c.setVariable("type","");c.setVariable("pageNumber",1);c.setVariable("result",new AiFeedback.Result(List.of(),false,new AiFeedback.Page(List.of(),1,false),null));
            assertThat(engine.process("ai-feedback",c)).contains("data-feedback-editor","data-fe-consent","data-fe-confirm","data-fe-save disabled").doesNotContain("??feedbackEdit.","th:");
            c.setVariable("info",new DatabaseInfo("ADMIN","ADMIN","LOW","DB"));assertThat(engine.process("ai-profiles",c)).doesNotContain("data-ai-creation","data-ai-create=");
        }
    }
    @Test void schemasInvalidatePlansAndMutatingTokensHaveNoSqlField(){
        var session=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));var input=new AiCreation.Input("APP",AiCreation.Kind.PROFILE,"","v","P","","DISABLED","{}",false);var plan=new AiCreation.Plan(input,Map.of("exists",false),List.of());session.creation().put(plan);session.selectSchema("OTHER");assertThatThrownBy(()->session.creation().get(plan.token)).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);
        assertThat(Arrays.stream(Copy.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).containsExactly("token","index","consent");
    }
}
