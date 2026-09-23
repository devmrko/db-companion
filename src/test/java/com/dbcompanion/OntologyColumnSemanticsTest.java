package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyWizard.*;
import com.dbcompanion.service.*;
import java.io.StringReader;
import java.time.Instant;
import java.util.*;
import org.eclipse.rdf4j.rio.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyColumnSemanticsTest {
    final JsonMapper json=new JsonMapper();
    Definition definition(String valueMeaning,String labelColumn){return new Definition("Business area",List.of("Territory"),"","code","REVISE","Stored column context","Confirm the business scope","APP.P",Instant.EPOCH.toString(),2,valueMeaning,labelColumn);}
    Entry entry(Definition definition,String state,String labelSensitivity){
        var source=new Snapshot("DB","APP","AREAS","Business territories",List.of(
            new ColumnInfo(1,"CODE","VARCHAR2(20)","N","Business territory identifier"),
            new ColumnInfo(2,"LABEL","VARCHAR2(80)","N","Display name"),
            new ColumnInfo(3,"EMAIL","VARCHAR2(80)","Y","do-not-send")),List.of(),"now");
        var columns=new LinkedHashMap<>(Ontology.initial(source).columns());
        columns.put("CODE",new ColumnMeaning("Business territory code","UNKNOWN",definition));
        columns.put("LABEL",new ColumnMeaning("Territory name",labelSensitivity));
        return new Entry("1",1,"497c24e0-3b91-4083-ac24-550eea0a0821",state,"APP","now",new Document(1,source,new Meaning("Business areas","",columns,Map.of()),"USER",null));
    }
    Entry entry(){return entry(null,"DRAFT","UNKNOWN");}
    Sample sample(){var e=entry();return OntologyWizard.sanitize(e,OntologyWizard.select(e,List.of("CODE","LABEL"),true,10),List.of(List.of("A1","Western area"),List.of("B2","Eastern area")),Instant.EPOCH);}
    Map<String,Object> row(String column,String target){
        var value=new LinkedHashMap<String,Object>();value.put("column",column);value.put("description","Business territory field");value.put("label","Business territory");value.put("aliases",List.of("Service area"));value.put("unit","");value.put("role",column.equals("CODE")?"code":"name");value.put("valueMeaning",column.equals("CODE")?"Business territory identifiers":"Display names of business territories");value.put("labelColumn",target);value.put("assessment","REVISE");value.put("reason","Stored column definitions");value.put("uncertainty","Business scope needs confirmation");return value;
    }
    String output(Map<String,Object> first){return json.writeValueAsString(Map.of("columns",List.of(first,row("LABEL",""))));}
    @Test void legacyDefinitionsOmitNewFieldsAndPreserveTheirJsonAndHash(){
        var legacy=new Definition("Business area",List.of("Territory"),"","code","UNKNOWN","Evidence","Verify","APP.P","now",2);
        var e=entry(legacy,"DRAFT","UNKNOWN");String value=Ontology.json(e.document(),json);
        assertThat(value).doesNotContain("valueMeaning","labelColumn");
        assertThat(json.readTree(value).path("meaning").path("columns").path("CODE").path("definition").size()).isEqualTo(10);
        assertThat(Ontology.parse(value,json)).isEqualTo(e.document());
        assertThat(OntologyRelations.definitionHash(Ontology.parse(value,json))).isEqualTo(OntologyRelations.definitionHash(e.document()));
        assertThat(OntologyRelations.definitionHash(entry(definition("Business territory identifiers","LABEL"),"DRAFT","UNKNOWN").document())).isNotEqualTo(OntologyRelations.definitionHash(e.document()));
    }
    @Test void previewAddsOnlySampledColumnCommentsAndSafeStoredDefinitions(){
        var e=entry(definition("Business territory identifiers","LABEL"),"DRAFT","UNKNOWN");
        String payload=OntologyWizard.payload(sample(),e,json);
        assertThat(payload).contains("columnDefinitions","Business territory identifier","Display name","valueMeaning","labelColumn","DRAFT").doesNotContain("EMAIL","do-not-send");
        var single=OntologyWizard.sanitize(e,OntologyWizard.select(e,List.of("CODE"),true,10),List.of(List.of("A1")),Instant.EPOCH);
        assertThat(OntologyWizard.payload(single,e,json)).doesNotContain("LABEL","Display name","labelColumn");
    }
    @Test void excludedSampleColumnsCannotReturnViaDefinitionReferences(){
        var e=entry(definition("Business territory identifiers","LABEL"),"DRAFT","UNKNOWN");
        var sampled=OntologyWizard.sanitize(e,OntologyWizard.select(e,List.of("CODE","LABEL"),true,10),List.of(List.of("A1","person@example.test")),Instant.EPOCH);
        assertThat(sampled.excluded()).containsExactly("LABEL");
        assertThat(OntologyWizard.payload(sampled,e,json)).doesNotContain("LABEL","person@","labelColumn","Display name");
    }
    @Test void responseAcceptsWholeColumnSemanticsAndOnlySelectedOtherColumnReferences(){
        var records=OntologyWizard.parse(output(row("CODE","LABEL")),sample(),"APP.P",json);
        assertThat(records.getFirst().definition().valueMeaning()).isEqualTo("Business territory identifiers");
        assertThat(records.getFirst().definition().labelColumn()).isEqualTo("LABEL");
        for(String target:List.of("CODE","EMAIL","MISSING","OTHER.LABEL","LABEL; DELETE"))assertThatThrownBy(()->OntologyWizard.parse(output(row("CODE",target)),sample(),"APP.P",json)).isInstanceOf(Failure.class);
    }
    @Test void extendedSchemaIsStrictAndPrivacyAndStyleChecksCoverNewProse(){
        for(String value:List.of("Western area","person@example.test","x".repeat(161),"one\ntwo")){
            var row=row("CODE","");row.put("valueMeaning",value);
            assertThatThrownBy(()->OntologyWizard.parse(output(row),sample(),"APP.P",json)).isInstanceOf(Failure.class);
        }
        var partial=row("CODE","");partial.remove("labelColumn");assertThatThrownBy(()->OntologyWizard.parse(output(partial),sample(),"APP.P",json)).isInstanceOf(Failure.class);
        var bad=row("CODE","");bad.put("labelColumn",List.of("LABEL"));assertThatThrownBy(()->OntologyWizard.parse(output(bad),sample(),"APP.P",json)).isInstanceOf(Failure.class);
        var extra=row("CODE","");extra.put("values",List.of("A1","B2"));assertThatThrownBy(()->OntologyWizard.parse(output(extra),sample(),"APP.P",json)).isInstanceOf(Failure.class);
    }
    @Test void unknownAndLegacyOutputsDoNotInventAdditionalMeaning(){
        var unknown=row("CODE","");unknown.put("assessment","UNKNOWN");unknown.put("description","");unknown.put("valueMeaning","");
        var d=OntologyWizard.parse(output(unknown),sample(),"APP.P",json).getFirst().definition();assertThat(d.valueMeaning()).isEmpty();assertThat(d.labelColumn()).isEmpty();
        unknown.remove("valueMeaning");unknown.remove("labelColumn");
        assertThat(OntologyWizard.parse(output(unknown),sample(),"APP.P",json).getFirst().definition().valueMeaning()).isEmpty();
    }
    @Test void reviewMergesOnlySelectedColumnsAndKeepsExistingValueMappings(){
        var base=entry();var m=base.document().meaning();var binding=new OntologyValues.Binding("e8995335-a690-4d8c-8a08-7fe2e2562d17","CODE","TEXT","A1","Existing label",List.of(),"");
        var e=new Entry(base.seq(),base.revision(),base.documentId(),base.state(),base.actor(),base.recordedAt(),new Document(1,base.document().source(),new Meaning(m.concept(),m.description(),m.columns(),m.relations(),List.of(binding)),"USER",null));
        var records=OntologyWizard.parse(output(row("CODE","LABEL")),sample(),"APP.P",json);var proposal=new Proposal("t","APP","AREAS",1,records,Instant.now().plusSeconds(600));
        var saved=OntologyWizard.merge(e,proposal,List.of(new Edit("CODE","Area code","Area",List.of(),"","code","Reviewed domain","LABEL")));
        assertThat(saved.columns().get("CODE").definition().valueMeaning()).isEqualTo("Reviewed domain");
        assertThat(saved.columns().get("CODE").definition().reason()).isEqualTo("Stored column definitions");
        assertThat(saved.columns().get("LABEL")).isEqualTo(m.columns().get("LABEL"));assertThat(saved.valueMappings()).containsExactly(binding);
        assertThatThrownBy(()->OntologyWizard.merge(e,proposal,List.of(new Edit("CODE","x","x",List.of(),"","","x","EMAIL")))).isInstanceOf(Failure.class);
    }
    @Test void persistedDefinitionsRejectUnknownAndSelfReferences(){
        for(String target:List.of("CODE","OTHER.TABLE","MISSING")){
            var e=entry(definition("Business territory identifiers",target),"DRAFT","UNKNOWN");
            assertThatThrownBy(()->Ontology.parse(Ontology.json(e.document(),json),json)).isInstanceOf(Failure.class);
        }
        var e=entry(definition("Business territory identifiers","LABEL"),"DRAFT","UNKNOWN");assertThat(Ontology.parse(Ontology.json(e.document(),json),json)).isEqualTo(e.document());
    }
    @Test void rdfStoresColumnMetadataNotValueListsOrForeignKeys() throws Exception {
        var e=entry(definition("Business territory identifiers","LABEL"),"DRAFT","UNKNOWN");var export=OntologyRdf.export(e);
        assertThat(export.text()).contains("dbc:valueMeaning \"Business territory identifiers\"","dbc:labelColumn <"+export.tableIri()+"/column/LABEL>").doesNotContain("ValueMapping","ForeignKeyMetadata","A1","Western area");
        assertThat(Rio.parse(new StringReader(export.text()),"",RDFFormat.TURTLE)).isNotEmpty();
        assertThat(OntologyDiscovery.batches(List.of(e,new OntologyContextTest().entry("ANOTHER",List.of())),json).getFirst().source()).contains("dbc:valueMeaning","dbc:labelColumn");
    }
    @Test void sensitiveReferenceIsRemovedFromExternalRdfButHistoricalJsonIsKept(){
        var e=entry(definition("Business territory identifiers","LABEL"),"APPROVED","SENSITIVE");
        var filtered=OntologyContext.filtered(e,Map.of("AREAS",e));
        assertThat(OntologyRdf.render(filtered)).doesNotContain("/column/LABEL","dbc:labelColumn");
        assertThat(filtered.document().meaning().columns().get("CODE").definition().labelColumn()).isEmpty();
        assertThat(e.document().meaning().columns().get("CODE").definition().labelColumn()).isEqualTo("LABEL");
    }
    @Test void questionEvidenceUsesApprovedSemanticsAndNotDrafts(){
        for(String state:List.of("APPROVED","DRAFT")){
            var e=entry(definition("Business territory identifiers","LABEL"),state,"UNKNOWN");var data=new OntologyInquiry.Dataset("APP",List.of(e),OntologyRelations.analyze("DB","APP",List.of(e),"now"),"now");
            var search=OntologyInquiry.search(data,"Business areas","AREAS");var chosen=OntologyInquiry.choose(search,search.evidence().stream().filter(OntologyInquiry.Evidence::usable).map(OntologyInquiry.Evidence::id).toList());
            String payload=OntologyInquiry.payload(chosen,List.of(e),json);
            if(state.equals("APPROVED"))assertThat(payload).contains("dbc:valueMeaning","dbc:labelColumn");
            else assertThat(payload).doesNotContain("dbc:valueMeaning","dbc:labelColumn","Business territory identifiers");
        }
    }
    @Test void promptForbidsCodeEnumerationStandardsAndCardinalityGuesses(){
        var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"oci","m","v");
        var draft=new AiAssistant.Draft(new AiAssistant.Preview("t","T",profile,"{}",2,false,Instant.now()),"APP","ko","ontology-wizard");
        assertThat(OntologyWizard.prompt(draft)).contains("not a dictionary of individual values","Never enumerate codes","Do not assume a country-code standard","another supplied sample column","do not prove uniqueness","Do not infer additive aggregation rules");
        assertThat(OntologyDiscovery.prompt(new OntologyDiscovery.Batch(List.of("T"),"{}"),"ko")).contains("not a foreign key or an equality join","cannot establish a full code list");
    }
}
