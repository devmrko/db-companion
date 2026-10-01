package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class OntologyDiscoveryDiagnosticsTest {
    final OntologyPipelineTest fixture=new OntologyPipelineTest();
    final List<Ontology.Entry> entries=List.of(fixture.orders(),fixture.customer());
    final OntologyDiscovery.Batch batch=OntologyDiscovery.batches(entries,fixture.json).getFirst();
    String response(){return fixture.response("ORDERS","BUYER_NO");}
    String field(String key,Object value){var root=fixture.json.readTree(response());((ObjectNode)root.path("relations").get(0)).set(key,fixture.json.valueToTree(value));return fixture.json.writeValueAsString(root);}
    OntologyDiscovery.ResponseFailure rejected(String output,String code,String path){
        var failure=catchThrowableOfType(()->OntologyDiscovery.parse(output,batch,fixture.data(entries),fixture.profile,fixture.json),OntologyDiscovery.ResponseFailure.class);
        assertThat(failure).isNotNull();assertThat(failure.status()).isEqualTo(422);
        assertThat(failure.diagnostic().code()).isEqualTo(code);assertThat(failure.diagnostic().path()).isEqualTo(path);
        if(output!=null&&!output.isBlank()&&output.length()<=AiAssistant.MAX_RESULT)assertThat(failure.diagnostic().rawResponse()).isEqualTo(output);
        if(output!=null&&!output.isBlank())assertThat(failure.getMessage()).doesNotContain(output);
        return failure;
    }
    @Test void syntaxAndEnvelopeFailuresHaveBoundedDiagnostics(){
        rejected(null,"EMPTY","$");rejected(" ","EMPTY","$");rejected("```json\n"+response()+"\n```","JSON","$");
        rejected("{\"relations\":[","JSON","$");
        for(String value:List.of("null","[]","{}","{\"relations\":[],\"extra\":true}"))rejected(value,"ROOT","$.relations");
        assertThat(rejected("x".repeat(AiAssistant.MAX_RESULT+1),"SIZE","$").diagnostic().rawResponse()).isEmpty();
        var row=fixture.json.readTree(response()).path("relations").get(0);
        rejected(fixture.json.writeValueAsString(Map.of("relations",Collections.nCopies(101,row))),"COUNT","$.relations");
        rejected("{\"relations\":[{}]}","ITEM","$.relations[0]");
    }
    @Test void typeAndTextFailuresIdentifyTheExactFieldWithoutChangingLimits(){
        rejected(field("reason",null),"TYPE","$.relations[0].reason");
        rejected(field("label",17),"TYPE","$.relations[0].label");
        for(String key:List.of("source","target","label","reason","uncertainty"))rejected(field(key,""),"TEXT","$.relations[0]."+key);
        for(String value:List.of("x".repeat(81),"two\nlines","bad\u0000text"))rejected(field("label",value),"TEXT","$.relations[0].label");
        rejected(field("uncertainty","x".repeat(121)),"TEXT","$.relations[0].uncertainty");
    }
    @Test void tablesAndColumnsAreNotGuessedOrSilentlyCorrected(){
        rejected(field("source","OTHER"),"TABLE","$.relations[0].source");
        rejected(field("target","APP.CUSTOMER"),"TABLE","$.relations[0].target");
        rejected(field("sourceColumns",List.of("buyer_no")),"COLUMN","$.relations[0].sourceColumns[0]");
        rejected(field("targetColumns",List.of("MISSING")),"COLUMN","$.relations[0].targetColumns[0]");
        rejected(field("targetColumns",List.of(1)),"TYPE","$.relations[0].targetColumns[0]");
        for(Object value:List.of("BUYER_NO",List.of(),List.of(""),List.of("BUYER_NO","BUYER_NO"),Collections.nCopies(33,"BUYER_NO"))){
            var failure=catchThrowableOfType(()->OntologyDiscovery.parse(field("sourceColumns",value),batch,fixture.data(entries),fixture.profile,fixture.json),OntologyDiscovery.ResponseFailure.class);
            assertThat(failure.diagnostic().code()).isEqualTo("COLUMNS");assertThat(failure.diagnostic().path()).startsWith("$.relations[0].sourceColumns");
        }
        rejected(field("sourceColumns",List.of("BUYER_NO","ORDER_ID")),"COLUMNS","$.relations[0].sourceColumns/targetColumns");
    }
    @Test void sensitiveAndUnsupportedTypesKeepTheirExistingBoundary(){
        for(String condition:List.of("SENSITIVE","DATATYPE")){
            var tables=new ArrayList<>(fixture.data(entries).tables());var source=tables.getFirst();
            var columns=source.columns().stream().map(c->new OntologyRelations.Field(c.name(),condition.equals("DATATYPE")?"DATE":c.type(),c.description(),c.label(),c.aliases(),condition.equals("SENSITIVE"))).toList();
            tables.set(0,new OntologyRelations.Table(source.name(),source.documentId(),source.revision(),source.state(),source.concept(),source.description(),columns,source.keys()));
            var analysis=new OntologyRelations.Analysis("APP",tables,List.of(),"now");
            var failure=catchThrowableOfType(()->OntologyDiscovery.parse(response(),batch,analysis,fixture.profile,fixture.json),OntologyDiscovery.ResponseFailure.class);
            assertThat(failure.diagnostic().code()).isEqualTo(condition);assertThat(failure.diagnostic().path()).startsWith("$.relations[0].sourceColumns[0]");
        }
    }
    @Test void aBadSecondItemRejectsThatResponseAtomicallyWithItsIndex(){
        var first=fixture.json.readTree(response()).path("relations").get(0);
        var second=fixture.json.readTree(field("sourceColumns",List.of("MISSING"))).path("relations").get(0);
        rejected(fixture.json.writeValueAsString(Map.of("relations",List.of(first,second))),"COLUMN","$.relations[1].sourceColumns[0]");
        rejected(fixture.json.writeValueAsString(Map.of("relations",List.of(first,first))),"DUPLICATE","$.relations[1]");
        assertThat(OntologyDiscovery.parse(response(),batch,fixture.data(entries),fixture.profile,fixture.json)).hasSize(1);
        assertThat(OntologyDiscovery.parse("{\"relations\":[]}",batch,fixture.data(entries),fixture.profile,fixture.json)).isEmpty();
    }
    @Test void aFailedCallKeepsEarlierCandidatesAndDoesNotRetry(){
        var state=new OntologyPipeline.State();var now=Instant.now();
        state.prepare(new OntologyDiscovery.Plan("token","APP",fixture.profile,List.of(batch,batch,batch),2,now.plusSeconds(60)),entries.stream().map(OntologyContext::reference).toList());
        state.authorize("token",0,true,"APP",now);state.begin("token",0,true,"APP",now);
        state.finish(OntologyDiscovery.parse(response(),batch,fixture.data(entries),fixture.profile,fixture.json),true);
        state.begin("token",1,true,"APP",now);rejected(field("source","OTHER"),"TABLE","$.relations[0].source");state.finish(List.of(),false);
        assertThat(state.progress().completed()).isEqualTo(1);assertThat(state.progress().nextIndex()).isEqualTo(2);assertThat(state.progress().failed()).containsExactly(2);
        assertThat(state.augment(fixture.data(entries)).relations()).hasSize(1);
        assertThatThrownBy(()->state.begin("token",1,true,"APP",now)).isInstanceOf(Ontology.Failure.class);
    }
    @Test void controllerReturnsDiagnosticsOnlyToTheAuthenticatedRequest(){
        var failure=new OntologyDiscovery.ResponseFailure("JSON","$","<script>untrusted</script>");
        var service=new OntologyPipelineService(new com.dbcompanion.common.db.SessionDataSource(),null,null,null,null,fixture.json){
            @Override public OntologyDiscovery.Step generate(com.dbcompanion.common.db.PoolSession s,String token,int index,boolean consent,Locale locale){throw failure;}
        };
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        try(var session=OntologyImportServiceTest.session()){
        request.getSession().setAttribute(com.dbcompanion.common.db.PoolSession.ATTRIBUTE,session);
        var controller=new com.dbcompanion.controller.OntologyPipelineController(service);
        var result=controller.generate(new com.dbcompanion.controller.OntologyPipelineController.Step("token",1,true),Locale.KOREAN,request);
        assertThat(result.getStatusCode().value()).isEqualTo(422);assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(fixture.json.valueToTree(result.getBody()).path("diagnostic").path("rawResponse").asString()).isEqualTo(failure.diagnostic().rawResponse());
        var anonymous=controller.generate(new com.dbcompanion.controller.OntologyPipelineController.Step("token",1,true),Locale.KOREAN,new org.springframework.mock.web.MockHttpServletRequest());
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);assertThat(fixture.json.valueToTree(anonymous.getBody()).has("diagnostic")).isFalse();
        }
    }
    @Test void everyDiagnosticHasFourLanguageMessages(){
        var messages=com.dbcompanion.common.i18n.UiMessages.source();
        for(String language:List.of("ko","en","ja","zh-CN"))for(String code:List.of("EMPTY","SIZE","JSON","ROOT","COUNT","ITEM","TYPE","TEXT","TABLE","COLUMNS","COLUMN","SENSITIVE","DATATYPE","DUPLICATE"))
            assertThat(messages.getMessage("ontology.discovery.response."+code,null,Locale.forLanguageTag(language))).isNotBlank();
    }
}
