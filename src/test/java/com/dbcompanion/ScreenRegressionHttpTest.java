package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.assertj.core.api.Assertions.assertThat;

/** Real embedded HTTP and repository assets; no database substitution or fake authentication. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class ScreenRegressionHttpTest {
    static final List<String> MENUS=List.of("/","/business-glossary","/ontology","/ontology-query","/ai-assistant","/vector-search",
            "/tables","/ai-profiles","/ai-test","/ai-test/problems","/ai-feedback","/ai-agents","/ai-executions","/db/scheduler",
            "/db/external-sources","/db/credentials","/db/security","/db/functions","/db/ords");
    @LocalServerPort int port;
    @Autowired RequestMappingHandlerMapping mappings;
    final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void allMenusHaveARealGetHandlerAndRequireLogin() throws Exception {
        var getPaths=new HashSet<String>();
        mappings.getHandlerMethods().forEach((mapping,handler)->{
            if(mapping.getMethodsCondition().getMethods().contains(RequestMethod.GET))getPaths.addAll(mapping.getPatternValues());
        });
        for(String path:MENUS){
            assertThat(getPaths).as("GET handler for %s",path).contains(path);
            var response=get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(302);
            assertThat(response.headers().firstValue("location").orElse("")).as(path).endsWith("/login");
            assertThat(com.dbcompanion.controller.LanguageController.safeReturn(path)).as(path).isEqualTo(path);
        }
    }
    @Test void everyLocalJavascriptModuleIsServedWithJavascriptMimeAndResolvableImports() throws Exception {
        Path directory=Path.of("src/main/resources/static/js");
        try(var files=Files.list(directory)){
            for(Path file:files.filter(Files::isRegularFile).toList()){
                var response=get("/js/"+file.getFileName());
                assertThat(response.statusCode()).as(file.toString()).isEqualTo(200);
                assertThat(response.headers().firstValue("content-type").orElse("")).as(file.toString()).contains("javascript");
                var imports=Pattern.compile("(?:from\\s*|import\\s*)['\"](\\./[^'\"]+)['\"]").matcher(Files.readString(file));
                while(imports.find())assertThat(Files.isRegularFile(file.getParent().resolve(imports.group(1)))).as(file+" -> "+imports.group(1)).isTrue();
            }
        }
    }
    @Test void ordsMutationsRequireCsrfBeforeControllerOrDatabaseWork() throws Exception {
        for(String route:List.of("/db/ords/preview","/db/ords/apply")){
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+route)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
            assertThat(client.send(request.build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
    }
    @Test void everyTemplateScriptAndStylesheetHasARealLocalResource() throws Exception {
        var resources=new TreeSet<String>();
        try(var files=Files.walk(Path.of("src/main/resources/templates"))){
            for(Path file:files.filter(p->p.toString().endsWith(".html")).toList()){
                var matches=Pattern.compile("@\\{(/(?:js|css|webjars)/[^}]+)\\}").matcher(Files.readString(file));
                while(matches.find())resources.add(matches.group(1));
            }
        }
        assertThat(resources).contains("/css/common.css","/js/common.js");
        for(String resource:resources)assertThat(get(resource).statusCode()).as(resource).isEqualTo(200);
    }
}
