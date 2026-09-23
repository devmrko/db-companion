package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyAnalysis.*;
import com.dbcompanion.service.OntologyContext;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyResponseDiagnosticsTest {
    final JsonMapper json=new JsonMapper();
    final Context context=new Context(List.of(),List.of(),List.of());
    Recommendation row(String field,String name){return new Recommendation(field,name,"Business territory","Metadata description","Verify scope");}
    String output(Recommendation... rows){return json.writeValueAsString(Map.of("suggestions",List.of(rows)));}
    OntologyContext.ResponseDiagnostic rejected(String output,String code,String path){
        var failure=catchThrowableOfType(()->OntologyContext.parse(output,context,json),OntologyContext.ResponseFailure.class);
        assertThat(failure).isNotNull();assertThat(failure.status()).isEqualTo(422);
        assertThat(failure.diagnostic().code()).isEqualTo(code);assertThat(failure.diagnostic().path()).isEqualTo(path);
        if(output!=null&&!output.isBlank())assertThat(failure.getMessage()).doesNotContain(output);
        return failure.diagnostic();
    }
    Entry entry(String table,List<Key> keys){
        var source=new Snapshot("DB","APP",table,"Stored description",List.of(new ColumnInfo(1,"ID","NUMBER","N","Identifier")),keys,"now");
        return new Entry("1",1,UUID.nameUUIDFromBytes(table.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"DRAFT","APP","now",new Document(1,source,Ontology.initial(source),"CATALOG",null));
    }
    @Test void inboundOnlyTargetHasNoEditableRelationsEvenWhenNeighborRdfContainsThem(){
        var target=entry("PARENT",List.of());var child=entry("CHILD",List.of(new Key("FK_PARENT","R",List.of("ID"),"APP","PARENT",List.of("ID"),"ENABLED","VALIDATED")));
        var bundle=OntologyContext.bundle(target,List.of(child),json);var contract=json.readTree(bundle.payload()).path("responseContract");
        assertThat(bundle.payload()).contains("FK_PARENT");assertThat(bundle.context().relations()).isEmpty();
        assertThat(contract.path("maxItems").asInt()).isEqualTo(2);assertThat(contract.path("allowedItems").size()).isEqualTo(2);
        assertThat(contract.path("allowedItems").toString()).doesNotContain("relation","FK_PARENT");
        assertThat(OntologyContext.parse(output(row("concept",""),row("description","")),bundle.context(),json)).hasSize(2);
        assertThat(OntologyContext.parse("{\"suggestions\":[]}",bundle.context(),json)).isEmpty();
        rejected(output(row("relation","FK_PARENT")),"SCOPE","$.suggestions[0].field/name");
    }
    @Test void ownForeignKeysAreInAllowlistAndScopeRemainsExact(){
        var scoped=new Context(List.of(),List.of("EXACT_FK"),List.of());var contract=OntologyContext.responseContract(scoped);
        assertThat(contract.get("maxItems")).isEqualTo(3);
        assertThat(OntologyContext.parse(output(row("relation","EXACT_FK")),scoped,json)).hasSize(1);
        assertThatThrownBy(()->OntologyContext.parse(output(row("relation","exact_fk")),scoped,json)).isInstanceOf(OntologyContext.ResponseFailure.class);
    }
    @Test void syntaxRootAndCountHaveDistinctDiagnosticsWithoutRepairingResponses(){
        rejected("", "EMPTY","$");rejected(null,"EMPTY","$");
        var raw="```json\n"+output(row("concept",""))+"\n```";
        assertThat(rejected(raw,"JSON","$").rawResponse()).isEqualTo(raw);
        rejected("{\"suggestions\":[", "JSON","$");
        for(String invalid:List.of("[]","{}","null","{\"suggestions\":[],\"extra\":true}"))rejected(invalid,"ROOT","$.suggestions");
        rejected(output(row("concept",""),row("description",""),row("relation","NEIGHBOR")),"COUNT","$.suggestions");
    }
    @Test void itemShapeTypeAndScopeShowExactLocation(){
        rejected("{\"suggestions\":[{}]}","ITEM","$.suggestions[0]");
        rejected(output(row("concept","")).replace("\"reason\":\"Metadata description\"","\"reason\":null"),"TYPE","$.suggestions[0].reason");
        rejected(output(row("concept","PARENT")),"SCOPE","$.suggestions[0].field/name");
        rejected(output(row("concept",""),row("column","ID")),"SCOPE","$.suggestions[1].field/name");
    }
    @Test void duplicateBlankAndStyleAreNotSilentlyDroppedOrTruncated(){
        rejected(output(row("concept",""),row("concept","")),"DUPLICATE","$.suggestions[1].field/name");
        rejected(output(new Recommendation("concept",""," ","evidence","")),"VALUE","$.suggestions[0].value");
        for(String text:List.of("x".repeat(81),"권역으로 추정됨","two\nlines")){
            String raw=output(new Recommendation("concept","",text,"evidence",""));
            assertThat(rejected(raw,"STYLE","$.suggestions[0].value/reason/uncertainty").rawResponse()).isEqualTo(raw);
        }
    }
    @Test void oversizedResponseIsNotCopiedIntoDiagnosticsAndRawIsNotInExceptionText(){
        assertThat(rejected("x".repeat(AiAssistant.MAX_RESULT+1),"SIZE","$").rawResponse()).isEmpty();
        String raw="<script>alert('not executable')</script>";var diagnostic=rejected(raw,"JSON","$");
        assertThat(json.readTree(json.writeValueAsString(diagnostic)).path("rawResponse").asString()).isEqualTo(raw);
        assertThat(json.readTree(json.writeValueAsString(diagnostic)).size()).isEqualTo(3);
    }
    @Test void controllerOnlyExposesBoundedParseDiagnosticAndKeepsNoStore() throws Exception {
        String controller=Files.readString(Path.of("src/main/java/com/dbcompanion/controller/OntologyController.java"));
        assertThat(controller).contains("OntologyContext.ResponseFailure ex","\"diagnostic\",ex.diagnostic()","header(\"Cache-Control\",\"no-store\")");
        String service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/OntologyService.java"));
        assertThat(service).contains("OntologyContext.parse(output,preview.context(),json)").doesNotContain("log.","rawResponse");
    }
    @Test void errorLabelsAndBoundedScopeAreTranslated(){
        var messages=com.dbcompanion.common.i18n.UiMessages.source();
        for(String language:List.of("ko","en","ja","zh-CN")){
            var locale=Locale.forLanguageTag(language);
            for(String code:List.of("EMPTY","SIZE","JSON","ROOT","COUNT","ITEM","TYPE","SCOPE","DUPLICATE","VALUE","STYLE"))assertThat(messages.getMessage("ontology.context.response."+code,null,locale)).isNotBlank();
            assertThat(messages.getMessage("ontology.context.scope",new Object[]{"APP.PARENT",0},locale)).contains("APP.PARENT","0");
        }
    }
}
