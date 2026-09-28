package com.dbcompanion;

import java.net.URI;
import java.net.http.*;
import java.util.List;
import com.dbcompanion.controller.OntologyController.*;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class OntologyHttpTest {
    @LocalServerPort int port;
    @Autowired JsonMapper json;

    @Test void graphRequiresLoginAndRendererIsServedLocally() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var graph=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ontology/graph?schema=APP")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(graph.statusCode()).isEqualTo(302);
        var script=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/webjars/cytoscape/3.34.1/dist/cytoscape.min.js")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(script.statusCode()).isEqualTo(200);assertThat(script.body()).containsIgnoringCase("cytoscape");
    }
    @Test void pipelinePostsRequireCsrfBeforeAnyDbOrAiAction() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:List.of("preview","status","payload","resume","generate","stop","graph/preview","graph/create","scope/options","scope/profile","scope/saved","scope/save","scope/install")){
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ontology/pipeline/"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(403);
        }
    }
    @Test void relationshipAnalysisRequiresLoginAndReviewRequiresCsrf() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var get=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ontology/relationships?schema=APP")).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(get.statusCode()).isEqualTo(302);
        var post=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ontology/relationships/review")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(post.statusCode()).isEqualTo(403);
    }
    @Test void codeLookupRequiresCsrfAndModuleIsServedLocally() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ontology/values/lookup")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"schema\":\"APP\",\"confirmed\":true}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        var script=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/js/ontology-values.mjs")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(script.statusCode()).isEqualTo(200);assertThat(script.body()).contains("export function valuesEditor");
    }

    @Test void browserInstallPayloadDoesNotRequireAnUnrelatedRevision() {
        var request=json.readValue("{\"schema\":\"APP\",\"confirmed\":true}",Install.class);
        assertThat(request).isEqualTo(new Install("APP",true));
    }

    @Test void browserCaptureAndPreviewPayloadsDoNotRequireInstallConfirmation() {
        assertThat(json.readValue("{\"schema\":\"APP\",\"table\":\"T\"}",Capture.class))
                .isEqualTo(new Capture("APP","T"));
        assertThat(json.readValue("{\"schema\":\"APP\",\"table\":\"T\",\"revision\":1}",PreviewRequest.class))
                .isEqualTo(new PreviewRequest("APP","T",1));
    }

    @Test void browserSaveGenerateAndApplyPayloadsStillBind() {
        var save=json.readValue("""
                {"schema":"APP","table":"T","revision":1,"state":"DRAFT",
                 "meaning":{"concept":"Table","description":"Description","columns":{},"relations":{}}}
                """,Save.class);
        assertThat(save.revision()).isEqualTo(1);
        assertThat(save.meaning().description()).isEqualTo("Description");
        assertThat(json.readValue("{\"token\":\"preview-token\",\"consent\":true}",Generate.class))
                .isEqualTo(new Generate("preview-token",true));
        assertThat(json.readValue("{\"schema\":\"APP\",\"table\":\"T\",\"revision\":1,\"token\":\"proposal-token\",\"edits\":[{\"field\":\"concept\",\"name\":\"\",\"value\":\"주문\"}]}",Apply.class))
                .isEqualTo(new Apply("APP","T",1,"proposal-token",List.of(new com.dbcompanion.model.OntologyAnalysis.Edit("concept","","주문"))));
    }

    @Test void requiredConfirmationAndRevisionRemainStrict() {
        assertThat(json.readValue("{\"schema\":\"APP\",\"table\":\"T\",\"confirmed\":true}",CaptureMissing.class))
                .isEqualTo(new CaptureMissing("APP","T",true));
        assertThatThrownBy(()->json.readValue("{\"schema\":\"APP\",\"table\":\"T\"}",CaptureMissing.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThatThrownBy(()->json.readValue("{\"schema\":\"APP\",\"table\":\"T\",\"confirmed\":null}",CaptureMissing.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThatThrownBy(()->json.readValue("{\"schema\":\"APP\"}",Install.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThatThrownBy(()->json.readValue("{\"schema\":\"APP\",\"confirmed\":null}",Install.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThatThrownBy(()->json.readValue("{\"schema\":\"APP\",\"table\":\"T\"}",PreviewRequest.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThatThrownBy(()->json.readValue("{\"schema\":\"APP\",\"table\":\"T\",\"revision\":null}",PreviewRequest.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
    }
    @Test void wizardPayloadsBindAndMissingConsentIsRejected(){
        var request=json.readValue("{\"schema\":\"APP\",\"table\":\"T\",\"revision\":1,\"columns\":[\"C\"],\"count\":10,\"confirmed\":true}",com.dbcompanion.controller.OntologyWizardController.SampleRequest.class);
        assertThat(request.columns()).containsExactly("C");assertThat(request.confirmed()).isTrue();
        assertThatThrownBy(()->json.readValue("{\"token\":\"t\"}",com.dbcompanion.controller.OntologyWizardController.Generate.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
        var old=json.readValue("{\"description\":\"d\",\"sensitivity\":\"UNKNOWN\"}",com.dbcompanion.model.Ontology.ColumnMeaning.class);assertThat(old.definition()).isNull();
    }
    @Test void wizardRequiresAuthenticationAndCsrf() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var get=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ontology/wizard/options?schema=APP&table=T&revision=1")).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(get.statusCode()).isEqualTo(302);
        for(String path:List.of("sample","generate","apply","cancel")){var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ontology/wizard/"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).isEqualTo(403);}
    }
    @Test void rdfAnalysisCancelRequiresCsrfAndReviewEditsBind() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ontology/ai/cancel")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"test\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(json.readValue("{\"token\":\"test\"}",Cancel.class).token()).isEqualTo("test");
        assertThat(json.readValue("{\"schema\":\"APP\",\"table\":\"T\",\"revision\":1,\"token\":\"p\"}",Apply.class).edits()).isNull();
    }
    @Test void definitionBridgePayloadsKeepSearchAndOriginalQuestionSeparate() {
        var request=json.readValue("{\"schema\":\"APP\",\"searchQuery\":\"term\",\"originalQuestion\":\"  original question  \",\"tables\":[\"T\"],\"profile\":\"P\",\"token\":\"scope\",\"selected\":[\"T:1:TABLE:T\"]}",DefinitionPreview.class);
        assertThat(request.searchQuery()).isEqualTo("term");assertThat(request.originalQuestion()).isEqualTo("  original question  ");
        assertThat(json.readValue("{\"token\":\"scope\"}",Cancel.class)).isEqualTo(new Cancel("scope"));
    }
    @Test void definitionBridgeWritesRequireCsrfBeforeSessionOrProviderAccess() throws Exception {
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for(String path:List.of("context/preview","context/generate","context/cancel")){
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ontology/glossary/"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}" )).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
        }
    }
    @Test void unauthenticatedGetsAndCsrfLessWritesAreRejected() throws Exception {var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();for(String path:List.of("/ontology","/ontology/catalog?schema=APP","/ontology/detail?schema=APP&table=T","/ontology/rdf?schema=APP&table=T&revision=1","/ontology/history?schema=APP&table=T")){var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).isEqualTo(302);assertThat(r.headers().firstValue("location").orElse("")).endsWith("/login");}for(String path:List.of("install","capture","capture/missing","save","ai/preview","ai/generate","ai/apply")){var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/ontology/"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).isEqualTo(403);}}
}
