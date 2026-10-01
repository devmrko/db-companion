package com.dbcompanion;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real embedded server and HTTP requests. No mocks, substitute DB, or simulated login. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class HttpFlowTest {
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
    HttpClient client;

    @Test void upgradePayloadMatchesTheRunningApplicationsStrictJsonContract() {
        var mapper = context.getBean(tools.jackson.databind.json.JsonMapper.class);
        var request = mapper.readValue("""
                {"schema":"DEMO_APP","table":"DEMO_COUNTRY","enabled":false}
                """, com.dbcompanion.model.MetadataHistory.Toggle.class);
        assertThat(request.schema()).isEqualTo("DEMO_APP");
        assertThat(request.table()).isEqualTo("DEMO_COUNTRY");
        assertThat(request.enabled()).isFalse();
        assertThatThrownBy(() -> mapper.readValue("""
                {"schema":"DEMO_APP","table":"DEMO_COUNTRY"}
                """, com.dbcompanion.model.MetadataHistory.Toggle.class)).hasMessageContaining("boolean");
    }

    @Test void diagnosticControllerIsNotRegisteredByDefault() {
        assertThat(context.getBeansOfType(com.dbcompanion.controller.ProfileAuditProbeController.class)).isEmpty();
        assertThat(context.getBeansOfType(com.dbcompanion.service.ProfileAuditProbeService.class)).isEmpty();
        assertThat(context.getBeansOfType(com.dbcompanion.service.ProfileHistoryVerificationService.class)).isEmpty();
        assertThat(context.getBeansOfType(com.dbcompanion.repository.ProfileAuditProbeRepository.class)).isEmpty();
    }

    @BeforeEach void setup() {
        client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void actualServerRendersThymeleafAndSecurityHeaders() throws Exception {
        var response = get("/login");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Oracle DB 계정을 입력하세요.", "name=\"_csrf\"", "/css/common.css");
        assertThat(response.body()).doesNotContain("th:action=", "th:replace=");
        assertThat(response.headers().firstValue("content-security-policy")).isPresent();
        assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-store");
    }

    @Test void localBootstrapCssAndIconsAreServed() throws Exception {
        assertThat(get("/webjars/bootstrap/5.3.8/css/bootstrap.min.css").statusCode()).isEqualTo(200);
        assertThat(get("/css/common.css").body()).contains("--app-accent", ".app-btn", ".app-card");
        assertThat(get("/icons.svg").body()).contains("symbol id=\"database\"");
        assertThat(get("/js/common.js").statusCode()).isEqualTo(200);
        var tableScript = get("/js/table-list.mjs");
        assertThat(tableScript.statusCode()).isEqualTo(200);
        assertThat(tableScript.headers().firstValue("content-type").orElse("")).contains("javascript");
        assertThat(tableScript.body()).contains("export function pageOf");
        assertThat(get("/js/paged-list.mjs").statusCode()).isEqualTo(200);
        assertThat(get("/js/catalog-preview.mjs").statusCode()).isEqualTo(200);
        assertThat(get("/js/metadata-editor.mjs").statusCode()).isEqualTo(200);
        assertThat(get("/js/metadata-history.mjs").statusCode()).isEqualTo(200);
        assertThat(get("/js/profile-editor.mjs").statusCode()).isEqualTo(200);
        assertThat(get("/js/profile-fields.mjs").statusCode()).isEqualTo(200);
        assertThat(get("/js/profile-objects.mjs").statusCode()).isEqualTo(200);
        assertThat(get("/js/team-fields.mjs").statusCode()).isEqualTo(200);
        assertThat(get("/js/team-editor.mjs").statusCode()).isEqualTo(200);
        assertThat(get("/js/team-history.mjs").statusCode()).isEqualTo(200);
        for(String module:java.util.List.of("object-fields","object-editor","object-history"))assertThat(get("/js/"+module+".mjs").statusCode()).isEqualTo(200);
    }

    @Test void anonymousDashboardRedirectsToLogin() throws Exception {
        var response = get("/");
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location").orElse("")).endsWith("/login");
    }
    @Test void functionsExposeOnlyAuthenticatedReadRoutesAndLocalViewerResources() throws Exception {
        for(String path:java.util.List.of("/db/functions","/db/functions/list?schema=APP","/db/functions/detail?schema=APP&name=FN"))
            assertThat(get(path).statusCode()).isEqualTo(302);
        var mapping=context.getBean("requestMappingHandlerMapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var routes=mapping.getHandlerMethods().entrySet().stream().filter(e->e.getValue().getBeanType().equals(com.dbcompanion.controller.FunctionCatalogController.class)).toList();
        assertThat(routes).hasSize(3);routes.forEach(route->assertThat(route.getKey().getMethodsCondition().getMethods()).containsExactly(org.springframework.web.bind.annotation.RequestMethod.GET));
        assertThat(get("/js/functions.mjs").statusCode()).isEqualTo(200);assertThat(get("/js/source-viewer.mjs").statusCode()).isEqualTo(200);
    }

    @Test void deepSecurityExposesOnlyAuthenticatedGetRoutes() throws Exception {
        for(String path:java.util.List.of("/db/security","/db/security/list?schema=APP&kind=roles","/db/security/detail?schema=APP&kind=roles&name=R"))
            assertThat(get(path).statusCode()).isEqualTo(302);
        var mapping=context.getBean("requestMappingHandlerMapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var routes=mapping.getHandlerMethods().entrySet().stream().filter(e->e.getValue().getBeanType().equals(com.dbcompanion.controller.DeepDataSecurityController.class)).toList();
        assertThat(routes).hasSize(3);routes.forEach(route->assertThat(route.getKey().getMethodsCondition().getMethods()).containsExactly(org.springframework.web.bind.annotation.RequestMethod.GET));
        assertThat(get("/js/deep-data-security.mjs").statusCode()).isEqualTo(200);
    }

    @Test void vectorExplorerRequiresLoginAndCsrfAndOffersNoDataMutationRoutes() throws Exception {
        for(String path:java.util.List.of("/vector-search","/vector-search/metadata?schema=APP&table=T",
                "/vector-search/rows?schema=APP&table=T&vector=V","/vector-search/detail?schema=APP&table=T&vector=V&id=x"))
            assertThat(get(path).statusCode()).isEqualTo(302);
        assertThat(post("/vector-search/search","{}").statusCode()).isEqualTo(403);
        assertThat(post("/vector-search/oci-models","{}").statusCode()).isEqualTo(403);
        assertThat(get("/vector-search/oci-models").statusCode()).isEqualTo(302);
        assertThat(get("/js/vector-search.mjs").statusCode()).isEqualTo(200);
        var mapping=context.getBean("requestMappingHandlerMapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var routes=mapping.getHandlerMethods().entrySet().stream().filter(e->e.getValue().getBeanType().equals(com.dbcompanion.controller.VectorSearchController.class)).toList();
        assertThat(routes).hasSize(6);
        assertThat(routes.stream().filter(e->e.getKey().getMethodsCondition().getMethods().contains(org.springframework.web.bind.annotation.RequestMethod.POST))
                .flatMap(e->e.getKey().getPatternValues().stream()).toList()).containsExactlyInAnyOrder("/vector-search/search","/vector-search/oci-models");
    }
    @Test void rowHistoryRoutesRequireLoginAndExplicitCsrfPost() throws Exception {
        for(String path:java.util.List.of("/vector-search/history?schema=APP&table=T","/vector-search/history/state?schema=APP&table=T",
                "/vector-search/history/entry?schema=APP&table=T&seq=1"))assertThat(get(path).statusCode()).isEqualTo(302);
        assertThat(post("/vector-search/history","{}").statusCode()).isEqualTo(403);
        assertThat(get("/js/table-row-history.mjs").statusCode()).isEqualTo(200);
        var token=Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(get("/login").body());assertThat(token.find()).isTrue();
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/vector-search/history"))
                .header("Content-Type","application/json").header("X-CSRF-TOKEN",token.group(1))
                .POST(HttpRequest.BodyPublishers.ofString("{}" )).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
    }

    @Test void ociCatalogueJsonHasOnlyExplicitLookupFieldsAndRequiresLoginWithValidCsrf() throws Exception {
        var mapper=context.getBean(tools.jackson.databind.json.JsonMapper.class);
        String json="""
                {"query":{"credential":"CATALOG_CRED","region":"us-chicago-1","compartment":"ocid1.compartment.oc1..test"},"page":"","refresh":false}
                """;
        var request=mapper.readValue(json,com.dbcompanion.model.OciEmbeddingModels.Request.class);
        assertThat(request.query().credential()).isEqualTo("CATALOG_CRED");assertThat(request.refresh()).isFalse();
        var extra=mapper.readValue(json.replace("\"refresh\":false","\"prompt\":\"must not send\",\"refresh\":false"),com.dbcompanion.model.OciEmbeddingModels.Request.class);
        assertThat(mapper.writeValueAsString(extra)).doesNotContain("prompt","must not send");
        var token=Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(get("/login").body());assertThat(token.find()).isTrue();
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/vector-search/oci-models"))
                .header("Content-Type","application/json").header("X-CSRF-TOKEN",token.group(1))
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);assertThat(response.headers().firstValue("location").orElse("")).endsWith("/login");
    }

    @Test void vectorSearchUsesTheRunningJsonContractsAndRequiresExplicitCostConsent() {
        var mapper=context.getBean(tools.jackson.databind.json.JsonMapper.class);
        String json="""
                {"selection":{"schema":"APP","table":"T","vector":"V","content":"BODY"},
                 "text":"검색어","provider":"ocigenai","modelOwner":"","model":"cohere.embed-multilingual-v3.0",
                 "credential":"C","region":"us-chicago-1","inputType":"search_query","metric":"COSINE","k":10,"externalConsent":true}
                """;
        var request=mapper.readValue(json,com.dbcompanion.model.VectorSearch.Search.class);
        assertThat(request.selection().vector()).isEqualTo("V");assertThat(request.external()).isTrue();
        assertThatThrownBy(()->mapper.readValue(json.replace("\"externalConsent\":true","\"externalConsent\":false"),com.dbcompanion.model.VectorSearch.Search.class))
                .hasMessageContaining("외부 임베딩");
        assertThat(mapper.writeValueAsString(new com.dbcompanion.model.VectorSearch.Column("V","VECTOR",null))).contains("\"name\":\"V\"","\"type\":\"VECTOR\"");
    }

    @Test void aiExecutionHistoryIsAuthenticatedReadOnlyAndScriptIsServed() throws Exception {
        assertThat(get("/ai-executions").statusCode()).isEqualTo(302);
        assertThat(get("/ai-executions/detail?schema=APP&id=abc-123").statusCode()).isEqualTo(302);
        assertThat(post("/ai-executions", "").statusCode()).isEqualTo(403);
        assertThat(get("/js/execution-history.mjs").statusCode()).isEqualTo(200);
        var mapping = context.getBean("requestMappingHandlerMapping", org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var routes = mapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().getBeanType().equals(com.dbcompanion.controller.AiExecutionHistoryController.class)).toList();
        assertThat(routes).hasSize(2);
        routes.forEach(route -> assertThat(route.getKey().getMethodsCondition().getMethods())
                .containsExactly(org.springframework.web.bind.annotation.RequestMethod.GET));
    }

    @Test void mappedSqlIsAuthenticatedAndOnlyHasReadEndpoints() throws Exception {
        assertThat(get("/ai-executions/sql").statusCode()).isEqualTo(302);
        assertThat(get("/ai-executions/sql/detail?id=key").statusCode()).isEqualTo(302);
        assertThat(post("/ai-executions/sql", "").statusCode()).isEqualTo(403);
        assertThat(post("/ai-executions/sql/detail", "").statusCode()).isEqualTo(403);
        var mapping = context.getBean("requestMappingHandlerMapping", org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var routes = mapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().getBeanType().equals(com.dbcompanion.controller.AiMappedSqlController.class)).toList();
        assertThat(routes).hasSize(2);
        routes.forEach(route -> assertThat(route.getKey().getMethodsCondition().getMethods())
                .containsExactly(org.springframework.web.bind.annotation.RequestMethod.GET));
    }

    @Test void feedbackIsAuthenticatedAndGetOnly() throws Exception {
        assertThat(get("/ai-feedback").statusCode()).isEqualTo(302);
        assertThat(get("/ai-feedback?schema=APP&profile=P&id=AAABBBCCC000001abc").statusCode()).isEqualTo(302);
        assertThat(post("/ai-feedback", "").statusCode()).isEqualTo(403);
        var mapping = context.getBean("requestMappingHandlerMapping", org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var routes = mapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().getBeanType().equals(com.dbcompanion.controller.AiFeedbackController.class)).toList();
        assertThat(routes).hasSize(1);
        routes.forEach(route -> assertThat(route.getKey().getMethodsCondition().getMethods())
                .containsExactly(org.springframework.web.bind.annotation.RequestMethod.GET));
    }

    @Test void agentExecutionUsesAuthenticatedGetOnlyRoutes() throws Exception {
        for (String path : java.util.List.of("/ai-executions/agents", "/ai-executions/agents/run?schema=APP&runId=run-1",
                "/ai-executions/agents/task?schema=APP&runId=run-1&order=1", "/ai-executions/agents/conversations?schema=APP&runId=run-1&order=1")) {
            assertThat(get(path).statusCode()).isEqualTo(302);
            assertThat(post(path, "").statusCode()).isEqualTo(403);
        }
        assertThat(get("/js/agent-executions.mjs").statusCode()).isEqualTo(200);
        var mapping = context.getBean("requestMappingHandlerMapping", org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var routes = mapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().getBeanType().equals(com.dbcompanion.controller.AiAgentExecutionController.class)).toList();
        assertThat(routes).hasSize(3);
        routes.forEach(route -> assertThat(route.getKey().getMethodsCondition().getMethods())
                .containsExactly(org.springframework.web.bind.annotation.RequestMethod.GET));
    }

    @Test void loginWithoutCsrfIsRejected() throws Exception {
        var response = post("/login", "username=APP_USER&password=not-a-real-password");
        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test void realServiceReportsMissingWalletWithoutConnecting() throws Exception {
        var html = get("/login").body();
        var matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).isTrue();
        var token = URLEncoder.encode(matcher.group(1), StandardCharsets.UTF_8);
        var response = post("/login", "_csrf=" + token + "&username=APP_USER&password=not-a-real-password");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains(".env에 Wallet 경로를 설정해 주세요.");
        assertThat(response.body()).doesNotContain("not-a-real-password");
        assertThat(get("/").statusCode()).isEqualTo(302);
    }

    @Test void logoutPostWithoutCsrfIsRejected() throws Exception {
        assertThat(post("/logout", "").statusCode()).isEqualTo(403);
    }

    @Test void agentCatalogRoutesRequireAuthentication() throws Exception {
        assertThat(get("/ai-agents").statusCode()).isEqualTo(302);
        assertThat(get("/ai-agents/team?schema=APP&team=TEAM").statusCode()).isEqualTo(302);
        assertThat(get("/ai-agents/task?schema=APP&team=TEAM&task=TASK").statusCode()).isEqualTo(302);
        assertThat(get("/ai-agents/team/attribute?schema=APP&team=TEAM&attribute=agents").statusCode()).isEqualTo(302);
        assertThat(get("/ai-agents/team/history?schema=APP&team=TEAM").statusCode()).isEqualTo(302);
        assertThat(get("/ai-agents/team/history/entry?schema=APP&team=TEAM&seq=1").statusCode()).isEqualTo(302);
        assertThat(post("/ai-agents/team/attribute", "").statusCode()).isEqualTo(403);
        assertThat(post("/ai-agents/team/history/install", "").statusCode()).isEqualTo(403);
        for(String kind:java.util.List.of("AGENT","TASK","TOOL")) {
            assertThat(get("/ai-agents/object/attribute?schema=APP&kind="+kind+"&name=O&attribute=instruction").statusCode()).isEqualTo(302);
            assertThat(get("/ai-agents/object/history?schema=APP&kind="+kind+"&name=O").statusCode()).isEqualTo(302);
            assertThat(get("/ai-agents/object/history/entry?schema=APP&kind="+kind+"&name=O&seq=1").statusCode()).isEqualTo(302);
        }
        assertThat(post("/ai-agents/object/attribute", "").statusCode()).isEqualTo(403);
        assertThat(post("/ai-agents/object/history/install", "").statusCode()).isEqualTo(403);
        assertThat(get("/catalog-preview/profile?schema=APP&name=PROFILE").statusCode()).isEqualTo(302);
        assertThat(get("/catalog-preview/routine?schema=APP&name=FUNCTION").statusCode()).isEqualTo(302);
    }

    @Test void tableAndSchemaRoutesEnforceAuthenticationAndCsrf() throws Exception {
        assertThat(get("/ai-profiles/attribute?schema=APP&profile=P&attribute=model").statusCode()).isEqualTo(302);
        assertThat(get("/ai-profiles/attribute/objects?schema=APP&profile=P&owner=APP").statusCode()).isEqualTo(302);
        assertThat(post("/ai-profiles/attribute", "").statusCode()).isEqualTo(403);
        assertThat(get("/ai-profiles/history?schema=APP").statusCode()).isEqualTo(302);
        assertThat(get("/ai-profiles/history/state?schema=APP").statusCode()).isEqualTo(302);
        assertThat(post("/ai-profiles/history/toggle", "").statusCode()).isEqualTo(403);
        assertThat(post("/ai-profiles/history/collect", "").statusCode()).isEqualTo(403);
        assertThat(post("/ai-profiles/history/install", "").statusCode()).isEqualTo(403);
        assertThat(get("/tables/metadata?schema=APP&table=T&kind=comment").statusCode()).isEqualTo(302);
        assertThat(get("/tables/history/readiness?schema=APP&table=T").statusCode()).isEqualTo(302);
        assertThat(post("/tables/metadata", "").statusCode()).isEqualTo(403);
        assertThat(get("/tables/history/state?schema=APP&table=T").statusCode()).isEqualTo(302);
        assertThat(get("/tables/history?schema=APP&table=T").statusCode()).isEqualTo(302);
        assertThat(post("/tables/history/toggle", "").statusCode()).isEqualTo(403);
        assertThat(post("/tables/history/upgrade", "").statusCode()).isEqualTo(403);
        assertThat(post("/tables/history/audit-upgrade", "").statusCode()).isEqualTo(403);
        assertThat(get("/tables").statusCode()).isEqualTo(302);
        assertThat(get("/tables/profiles?schema=APP").statusCode()).isEqualTo(302);
        assertThat(get("/ai-profiles").statusCode()).isEqualTo(302);
        assertThat(get("/ai-profiles/detail?profile=TEST").statusCode()).isEqualTo(302);
        assertThat(get("/tables/detail?schema=SYS&table=DUAL&tab=columns").statusCode()).isEqualTo(302);
        assertThat(get("/tables/detail?schema=SYS&table=DUAL&tab=constraints").statusCode()).isEqualTo(302);
        assertThat(get("/tables/detail?schema=SYS&table=DUAL&tab=indexes").statusCode()).isEqualTo(302);
        assertThat(post("/schema", "schema=SYS&returnTo=/tables").statusCode()).isEqualTo(403);
        assertThat(post("/schema/refresh", "returnTo=/tables").statusCode()).isEqualTo(403);
    }
    @Test void profileAuditToggleRequiresExplicitBoolean() {
        var mapper = context.getBean(tools.jackson.databind.json.JsonMapper.class);
        var request = mapper.readValue("{\"schema\":\"APP\",\"enabled\":false}", com.dbcompanion.model.ProfileHistory.Toggle.class);
        assertThat(request.enabled()).isFalse();
        assertThatThrownBy(() -> mapper.readValue("{\"schema\":\"APP\"}", com.dbcompanion.model.ProfileHistory.Toggle.class)).hasMessageContaining("boolean");
        assertThatThrownBy(() -> mapper.readValue("{\"schema\":\"APP\",\"enabled\":null}", com.dbcompanion.model.ProfileHistory.Toggle.class)).hasMessageContaining("boolean");
    }
    @Test void unknownProfileAuditStateIsSerializedAsNullNotOff() {
        var mapper = context.getBean(tools.jackson.databind.json.JsonMapper.class);
        var state = new com.dbcompanion.model.ProfileHistory.State(true, null, false, false, "POLICY", "확인 권한 없음");
        var json = mapper.readTree(mapper.writeValueAsString(state));
        assertThat(json.path("enabled").isNull()).isTrue();
        assertThat(json.path("installed").booleanValue()).isTrue();
        assertThat(json.path("canManage").booleanValue()).isFalse();
        assertThat(json.path("canCollect").booleanValue()).isFalse();
    }
    @Test void actualProfileEditorHasGetPostMappingsAndRejectsUnknownPayloadFields() {
        var mapping=context.getBean("requestMappingHandlerMapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var methods=mapping.getHandlerMethods().entrySet().stream()
                .filter(e->e.getValue().getBeanType().equals(com.dbcompanion.controller.ProfileEditController.class))
                .flatMap(e->e.getKey().getMethodsCondition().getMethods().stream()).toList();
        assertThat(methods).contains(org.springframework.web.bind.annotation.RequestMethod.GET,org.springframework.web.bind.annotation.RequestMethod.POST);
        var mapper=context.getBean(tools.jackson.databind.json.JsonMapper.class);
        var body=mapper.readValue("{\"schema\":\"APP\",\"profile\":\"P\",\"attribute\":\"model\",\"value\":\"M\",\"version\":\"V\"}",com.dbcompanion.model.ProfileEdit.SaveRequest.class);
        assertThat(body.target().profile()).isEqualTo("P");
        assertThatThrownBy(()->mapper.readValue("{\"schema\":\"APP\",\"profile\":\"P\",\"attribute\":\"model\",\"value\":\"M\",\"version\":\"V\",\"sql\":\"unexpected\"}",com.dbcompanion.model.ProfileEdit.SaveRequest.class))
                .hasMessageContaining("sql");
    }
    @Test void actualTeamEditorMappingsAndStrictPayloadRejectObjectTypeEscalation() {
        var mapping=context.getBean("requestMappingHandlerMapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var methods=mapping.getHandlerMethods().entrySet().stream()
                .filter(e->e.getValue().getBeanType().equals(com.dbcompanion.controller.TeamEditController.class))
                .flatMap(e->e.getKey().getMethodsCondition().getMethods().stream()).toList();
        assertThat(methods).contains(org.springframework.web.bind.annotation.RequestMethod.GET,org.springframework.web.bind.annotation.RequestMethod.POST);
        var mapper=context.getBean(tools.jackson.databind.json.JsonMapper.class);
        var body=mapper.readValue("{\"schema\":\"APP\",\"team\":\"T\",\"attribute\":\"process\",\"value\":\"sequential\",\"version\":\"V\"}",com.dbcompanion.model.TeamEdit.SaveRequest.class);
        assertThat(body.target().team()).isEqualTo("T");
        assertThatThrownBy(()->mapper.readValue("{\"schema\":\"APP\",\"team\":\"T\",\"attribute\":\"process\",\"value\":\"sequential\",\"version\":\"V\",\"objectType\":\"AGENT\"}",com.dbcompanion.model.TeamEdit.SaveRequest.class)).hasMessageContaining("objectType");
        assertThatThrownBy(()->mapper.readValue("{\"schema\":\"APP\",\"team\":\"T\",\"sql\":\"unexpected\"}",com.dbcompanion.model.TeamEdit.HistoryTarget.class)).hasMessageContaining("sql");
    }
    @Test void actualObjectEditorMappingsAndJsonRejectArbitraryKindsAndUnknownFields() {
        var mapping=context.getBean("requestMappingHandlerMapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        assertThat(mapping.getHandlerMethods().values().stream().filter(m->m.getBeanType().equals(com.dbcompanion.controller.AgentObjectEditController.class)).count()).isEqualTo(5);
        var mapper=context.getBean(tools.jackson.databind.json.JsonMapper.class);
        var body=mapper.readValue("{\"schema\":\"APP\",\"kind\":\"AGENT\",\"name\":\"A\",\"attribute\":\"role\",\"value\":\"text\",\"version\":\"v\"}",com.dbcompanion.model.AgentObjectEdit.SaveRequest.class);
        assertThat(body.target().kind()).isEqualTo(com.dbcompanion.model.AgentCatalog.Kind.AGENT);
        assertThatThrownBy(()->mapper.readValue("{\"schema\":\"APP\",\"kind\":\"SYS\",\"name\":\"X\"}",com.dbcompanion.model.AgentObjectEdit.HistoryTarget.class)).hasMessageContaining("SYS");
        assertThatThrownBy(()->mapper.readValue("{\"schema\":\"APP\",\"kind\":\"TOOL\",\"name\":\"X\",\"sql\":\"unexpected\"}",com.dbcompanion.model.AgentObjectEdit.HistoryTarget.class)).hasMessageContaining("sql");
        assertThatThrownBy(()->mapper.readValue("{\"schema\":\"APP\",\"kind\":\"TASK\",\"name\":\"X\",\"objectType\":\"TEAM\"}",com.dbcompanion.model.AgentObjectEdit.SaveRequest.class)).hasMessageContaining("objectType");
    }
}
