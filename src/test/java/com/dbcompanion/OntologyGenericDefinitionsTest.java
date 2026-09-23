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

class OntologyGenericDefinitionsTest {
    final JsonMapper json=new JsonMapper();
    final OntologyColumnSemanticsTest fixture=new OntologyColumnSemanticsTest();
    Definition definition(String role,String unit,String representation,String target,String usage){
        return new Definition("Business field",List.of("Alternate label"),unit,role,"REVISE","Column comments","","APP.P","now",2,representation,target,usage);
    }
    String payload(Entry e,boolean includeDefinition){
        var data=new OntologyInquiry.Dataset("APP",List.of(e),OntologyRelations.analyze("DB","APP",List.of(e),"now"),"now");
        var search=OntologyInquiry.search(data,"Business areas","AREAS");
        var chosen=OntologyInquiry.choose(search,search.evidence().stream().filter(OntologyInquiry.Evidence::usable).filter(x->includeDefinition||!x.kind().equals("DEFINITION")).map(OntologyInquiry.Evidence::id).toList());
        return OntologyInquiry.payload(chosen,List.of(e),json);
    }
    @Test void oldDefinitionsKeepJsonShapeAndHashes(){
        var old=fixture.entry(fixture.definition("Abbreviations","LABEL"),"DRAFT","UNKNOWN");
        String saved=Ontology.json(old.document(),json);assertThat(saved).doesNotContain("usageGuidance");
        var restored=Ontology.parse(saved,json);assertThat(restored.meaning().columns().get("CODE").definition().usageGuidance()).isEmpty();
        assertThat(OntologyRelations.definitionHash(restored)).isEqualTo(OntologyRelations.definitionHash(old.document()));
    }
    @Test void newRecommendationAcceptsUsageButRejectsUnknownFieldsTypesAndRawSamples(){
        var row=fixture.row("CODE","LABEL");row.put("usageGuidance","Filter by the supplied business code");
        var parsed=OntologyWizard.parse(fixture.output(row),fixture.sample(),"APP.P",json);
        assertThat(parsed.getFirst().definition().usageGuidance()).isEqualTo(row.get("usageGuidance"));
        for(Object invalid:List.of(12,List.of("x"),"x".repeat(161),"line\nbreak","Western area","person@example.test")){
            row.put("usageGuidance",invalid);assertThatThrownBy(()->OntologyWizard.parse(fixture.output(row),fixture.sample(),"APP.P",json)).isInstanceOf(Failure.class);
        }
        row.remove("usageGuidance");row.put("unknownField","");assertThatThrownBy(()->OntologyWizard.parse(fixture.output(row),fixture.sample(),"APP.P",json)).isInstanceOf(Failure.class);
    }
    @Test void reviewedUsageSurvivesDraftMergeAndJson(){
        var row=fixture.row("CODE","LABEL");row.put("usageGuidance","Filter by business abbreviation");
        var rec=OntologyWizard.parse(fixture.output(row),fixture.sample(),"APP.P",json);
        var proposal=new Proposal("t","APP","AREAS",1,rec,Instant.now().plusSeconds(600));
        var meaning=OntologyWizard.merge(fixture.entry(),proposal,List.of(new Edit("CODE","Code","Code",List.of(),"","code","Abbreviations","LABEL","Use code for filtering")));
        var doc=new Document(1,fixture.entry().document().source(),meaning,"AI_REVIEWED","APP.P");
        assertThat(Ontology.parse(Ontology.json(doc,json),json).meaning().columns().get("CODE").definition().usageGuidance()).isEqualTo("Use code for filtering");
    }
    @Test void manualCreationCannotClaimAiEvidence(){
        var before=fixture.entry();var supplied=fixture.entry(definition("code","","Abbreviations","LABEL","Use code for filtering"),"DRAFT","UNKNOWN").document().meaning();
        var result=OntologyWizard.manualMeaning(before,supplied);var d=result.columns().get("CODE").definition();
        assertThat(d.usageGuidance()).isEqualTo("Use code for filtering");assertThat(d.profile()).isEmpty();assertThat(d.reason()).isEmpty();assertThat(d.sampledAt()).isEmpty();assertThat(d.sampleRows()).isZero();assertThat(d.assessment()).isEqualTo("UNKNOWN");
        var entry=fixture.entry(d,"APPROVED","UNKNOWN");assertThat(OntologyRdf.render(entry)).contains("dbc:usageGuidance").doesNotContain("dbc:DefinitionReview");
    }
    @Test void manualEditsPreserveOriginalAiEvidenceOnly(){
        var before=fixture.entry(fixture.definition("Original","LABEL"),"DRAFT","UNKNOWN");
        var supplied=fixture.entry(definition("code","","New meaning","LABEL","Reviewed use"),"DRAFT","UNKNOWN").document().meaning();
        var d=OntologyWizard.manualMeaning(before,supplied).columns().get("CODE").definition();
        assertThat(d.reason()).isEqualTo("Stored column context");assertThat(d.uncertainty()).isEqualTo("Confirm the business scope");assertThat(d.usageGuidance()).isEqualTo("Reviewed use");
        assertThat(before.document().meaning().columns().get("CODE").definition().valueMeaning()).isEqualTo("Original");
    }
    @Test void usageLimitsAndLabelReferencesAreStillValidated(){
        for(String usage:List.of("x".repeat(501),"a\0b"))assertThatThrownBy(()->OntologyWizard.validate(definition("code","","Code","LABEL",usage))).isInstanceOf(Failure.class);
        var invalid=fixture.entry(definition("code","","Code","MISSING","Use code"),"DRAFT","UNKNOWN");
        assertThatThrownBy(()->OntologyWizard.manualMeaning(fixture.entry(),invalid.document().meaning())).isInstanceOf(Failure.class);
    }
    @Test void rdfPreservesUsageAsMetadataAndParses() throws Exception {
        var e=fixture.entry(definition("code","","Business abbreviations","LABEL","Use code for filtering"),"APPROVED","UNKNOWN");
        String rdf=OntologyRdf.render(e);assertThat(rdf).contains("dbc:usageGuidance \"Use code for filtering\"");
        assertThat(Rio.parse(new StringReader(rdf),"",RDFFormat.TURTLE)).isNotEmpty();
        assertThat(rdf).doesNotContain("ValueMapping","ForeignKeyMetadata");
    }
    @Test void approvedQueryReceivesStructuredDefinitionsWithoutAiProvenanceOrSamples(){
        var e=fixture.entry(definition("code","","Business abbreviations","LABEL","Use code for filtering"),"APPROVED","UNKNOWN");
        var rows=json.readTree(payload(e,true)).path("approvedColumnDefinitions");assertThat(rows.size()).isEqualTo(2);
        var code=rows.get(0);assertThat(code.path("column").asString()).isEqualTo("CODE");assertThat(code.path("labelColumn").asString()).isEqualTo("LABEL");assertThat(code.path("usageGuidance").asString()).isEqualTo("Use code for filtering");
        assertThat(rows.toString()).doesNotContain("profile","reason","sampleRows","EMAIL","Western area");
    }
    @Test void draftUnselectedAndSensitiveDefinitionsNeverEnterStructuredEvidence(){
        var d=definition("code","","Business abbreviations","LABEL","Use code for filtering");
        assertThat(json.readTree(payload(fixture.entry(d,"DRAFT","UNKNOWN"),true)).path("approvedColumnDefinitions").isEmpty()).isTrue();
        assertThat(json.readTree(payload(fixture.entry(d,"APPROVED","UNKNOWN"),false)).path("approvedColumnDefinitions").isEmpty()).isTrue();
        var rows=json.readTree(payload(fixture.entry(d,"APPROVED","SENSITIVE"),true)).path("approvedColumnDefinitions");
        assertThat(rows.size()).isEqualTo(1);assertThat(rows.get(0).path("labelColumn").asString()).isEmpty();
    }
    @Test void differentSemanticRolesUseTheSameContractWithoutNameRules(){
        for(var fields:List.of(List.of("DEPARTMENTS","DEPT_KEY","CAPTION","VARCHAR2(12)","code","","Business abbreviations","Filter by business code"),List.of("PRODUCTS","SKU","TITLE","VARCHAR2(30)","code","","Product identifiers","Filter by product code"),List.of("PAYMENTS","VALUE_X","","NUMBER(12,2)","amount","USD","Money per transaction","Display the transaction amount"),List.of("EVENTS","AT_X","","DATE","date","","Calendar date","Filter by the requested date"))){
            var columns=new ArrayList<ColumnInfo>();columns.add(new ColumnInfo(1,fields.get(1),fields.get(3),"N","Business field"));
            if(!fields.get(2).isEmpty())columns.add(new ColumnInfo(2,fields.get(2),"VARCHAR2(80)","N","Display name"));
            var source=new Snapshot("OTHER_DB","OTHER_SCHEMA",fields.get(0),"Business data",columns,List.of(),"now");
            var cm=new LinkedHashMap<>(Ontology.initial(source).columns());cm.put(fields.get(1),new ColumnMeaning("Approved field meaning","NON_SENSITIVE",definition(fields.get(4),fields.get(5),fields.get(6),fields.get(2),fields.get(7))));
            var e=new Entry("1",2,"497c24e0-3b91-4083-ac24-550eea0a0821","APPROVED","USER","now",new Document(1,source,new Meaning("Business entity","",cm,Map.of()),"USER",null));
            var data=new OntologyInquiry.Dataset(source.schema(),List.of(e),OntologyRelations.analyze(source.database(),source.schema(),List.of(e),"now"),"now");
            var search=OntologyInquiry.search(data,"Business entity",source.table());var chosen=OntologyInquiry.choose(search,search.evidence().stream().filter(OntologyInquiry.Evidence::usable).map(OntologyInquiry.Evidence::id).toList());
            var sent=json.readTree(OntologyInquiry.payload(chosen,List.of(e),json)).path("approvedColumnDefinitions").get(0);
            assertThat(sent.path("schema").asString()).isEqualTo(source.schema());assertThat(sent.path("table").asString()).isEqualTo(fields.get(0));assertThat(sent.path("column").asString()).isEqualTo(fields.get(1));
            assertThat(sent.path("role").asString()).isEqualTo(fields.get(4));assertThat(sent.path("labelColumn").asString()).isEqualTo(fields.get(2));assertThat(sent.path("usageGuidance").asString()).isEqualTo(fields.get(7));
        }
    }
    @Test void literalSelectionIsNotBlockedByLackOfAnEnumeratedDictionary(){
        String guidance=OntologyInquiry.COLUMN_GUIDANCE;
        assertThat(guidance).contains("not a prerequisite","literal already supplied","translating a term into a different stored code").doesNotContain("REGION_ID","REGION_NAME","DX_","TENANT","EU");
        var e=fixture.entry(definition("code","","Business abbreviations","LABEL","Filter by code"),"APPROVED","UNKNOWN");
        var evidence=new SelectAiEvidence.Snapshot("APP","A1 inventory","route",List.of(OntologyContext.reference(e)),"hash",payload(e,true),List.of(e));
        String prompt=SelectAiTest.prompt(SelectAiTest.Action.SQL,"A1 inventory","en",evidence);
        assertThat(prompt).contains(guidance,"approvedColumnDefinitions","Business abbreviations","Filter by code");
    }
}
