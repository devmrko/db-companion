package com.dbcompanion;

import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.controller.OntologyQueryController.*;
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
class OntologyQueryUiTest {
    @LocalServerPort int port;@Autowired JsonMapper json;
    @Test void realHttpRequiresLoginAndCsrfBeforeGenerationOrExecution() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:List.of("/ontology-query","/ontology-query/options?schema=APP","/ontology-query/archive/status?schema=APP","/ontology-query/archive/list?schema=APP","/ontology-query/archive/graph?schema=APP&id=x")){
            var res=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(res.statusCode()).isEqualTo(302);assertThat(res.headers().firstValue("location").orElse("")).endsWith("/login");
        }
        for(String path:List.of("search","preview","generate","execute","cancel","invalidate","archive/install","archive/setup","archive/preview","archive/save")){
            var res=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ontology-query/"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(res.statusCode()).as(path).isEqualTo(403);
        }
        var script=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/js/ontology-query.mjs")).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(script.statusCode()).isEqualTo(200);assertThat(script.body()).contains("selectedRoute");
    }
    @Test void requestContractsUseServerTokensNotClientSqlAndRequireBooleanConsent(){
        assertThat(json.readValue("{\"schema\":\"APP\",\"question\":\"질문\",\"anchor\":\"\"}",Search.class).question()).isEqualTo("질문");
        assertThat(json.readValue("{\"id\":\"s\",\"mode\":\"SQL\",\"route\":\"P1\"}",Prepare.class).route()).isEqualTo("P1");
        assertThat(json.readValue("{\"token\":\"x\",\"confirmed\":true}",Execute.class)).isEqualTo(new Execute("x",true));
        assertThatThrownBy(()->json.readValue("{\"token\":\"x\"}",Generate.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThatThrownBy(()->json.readValue("{\"token\":\"x\",\"confirmed\":null}",Execute.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThat(Arrays.stream(Execute.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).containsExactly("token","confirmed");
        var setup=json.readValue("{\"schema\":\"APP\",\"tablespace\":\"DATA\",\"confirmed\":true}",com.dbcompanion.controller.OntologyArchiveController.Setup.class);
        assertThat(setup.tablespace()).isEqualTo("DATA");
        assertThatThrownBy(()->json.readValue("{\"token\":\"x\"}",com.dbcompanion.controller.OntologyArchiveController.Save.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThat(Arrays.stream(com.dbcompanion.controller.OntologyArchiveController.Save.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).containsExactly("token","confirmed");
    }
    @Test void fourLanguagesRenderEscapedSchemaAndNoAutomaticAiOrExecution(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(String language:List.of("ko","en","zh-CN","ja")){
            var c=new Context(Locale.forLanguageTag(language));c.setVariable("activePage","ontology-query");c.setVariable("info",new DatabaseInfo("APP","APP","LOW","DB"));c.setVariable("schemas",List.of("<schema>"));c.setVariable("selectedSchema","<schema>");c.setVariable("languageReturn","/ontology-query");c.setVariable("_csrf",new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","render-only"));
            String html=engine.process("ontology-query",c);assertThat(html).contains("&lt;schema&gt;","data-oq-consent","data-oq-reviewed","data-oq-execute disabled","data-oq-evidence hidden","aria-labelledby=\"oq-preview-title\"","aria-live=\"polite\"").doesNotContain("??ontology.","th:","onclick=","onload=","data-schema=\"<schema>");
            assertThat(html.split("value=\"/ontology-query\"",-1)).hasSize(4);
            assertThat(html).contains("SKOS", "prefLabel", "altLabel");
            assertThat(html).contains("data-oa-preview disabled","data-oa-save disabled","data-oa-install hidden","data-oa-dialog","CONSTRUCT","DBC_RDF");
        }
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/ontology-query")).isEqualTo("/ontology-query");assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/ontology-query/execute")).isEqualTo("/login");
    }
}
