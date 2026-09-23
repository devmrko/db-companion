package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyValues.*;
import com.dbcompanion.service.*;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.eclipse.rdf4j.rio.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyValuesTest {
    final JsonMapper json=new JsonMapper();
    Binding binding(String column,String type,String value,String label,List<String> aliases){return new Binding(UUID.randomUUID().toString(),column,type,value,label,aliases,"Reviewed code definition");}
    Binding europe(){return binding("CODE","TEXT","EU","Europe",List.of("유럽"));}
    Entry entry(String table,String state,List<Binding> values){
        var source=new Snapshot("DB","APP",table,"",List.of(new ColumnInfo(1,"CODE","VARCHAR2(30 BYTE)","N",""),new ColumnInfo(2,"LABEL","VARCHAR2(100)","Y",""),new ColumnInfo(3,"N","NUMBER(10,0)","Y",""),new ColumnInfo(4,"EMAIL","VARCHAR2(200)","Y","")),List.of(),"now");
        var initial=Ontology.initial(source);var meaning=new Meaning("","",initial.columns(),initial.relations(),values);
        return new Entry("1",1,UUID.nameUUIDFromBytes(table.getBytes(StandardCharsets.UTF_8)).toString(),state,"APP","now",new Document(1,source,meaning,"USER",null));
    }
    Entry entry(List<Binding> values){return entry("REGIONS","APPROVED",values);}
    OntologyInquiry.Dataset dataset(List<Entry> entries){return new OntologyInquiry.Dataset("APP",entries,OntologyRelations.analyze("DB","APP",entries,"now"),"now");}
    @Test void oldJsonAndDefinitionHashesKeepTheirExactShape() throws Exception {
        var e=entry(List.of());String serialized=Ontology.json(e.document(),json);assertThat(serialized).doesNotContain("valueMappings");
        assertThat(Ontology.parse(serialized,json)).isEqualTo(e.document());
        var historical=json.readTree(serialized);assertThat(historical.path("meaning").size()).isEqualTo(4);
        assertThat(OntologyRelations.definitionHash(Ontology.parse(serialized,json))).isEqualTo(OntologyRelations.definitionHash(e.document()));
        var extended=entry(List.of(europe()));assertThat(Ontology.parse(Ontology.json(extended.document(),json),json)).isEqualTo(extended.document());
        assertThat(OntologyRelations.definitionHash(extended.document())).isNotEqualTo(OntologyRelations.definitionHash(e.document()));
    }
    @Test void typedValidationRejectsUnknownSensitiveInvalidDuplicateAndOversizedValues(){
        assertThat(OntologyValues.canonical("TEXT","001")).isEqualTo("001");assertThat(OntologyValues.canonical("NUMBER","1.00")).isEqualTo("1");
        var valid=entry(List.of(europe(),binding("N","NUMBER","1e2","One hundred",List.of())));Ontology.validate(valid.document().source(),valid.document().meaning());
        for(var b:List.of(binding("MISSING","TEXT","EU","X",List.of()),binding("EMAIL","TEXT","EU","X",List.of()),binding("CODE","NUMBER","1","X",List.of()),binding("CODE","TEXT","a@sample.test","X",List.of()),binding("N","NUMBER","1 OR 1=1","X",List.of()),binding("CODE","TEXT","x".repeat(201),"X",List.of()),binding("CODE","TEXT","EU","X",List.of("x".repeat(81))))){
            var e=entry(List.of(b));assertThatThrownBy(()->Ontology.validate(e.document().source(),e.document().meaning())).isInstanceOf(Failure.class);
        }
        for(var values:List.of(List.of(europe(),europe()),List.of(binding("N","NUMBER","1","X",List.of()),binding("N","NUMBER","1.0","Y",List.of())))){
            var e=entry(values);assertThatThrownBy(()->Ontology.validate(e.document().source(),e.document().meaning())).isInstanceOf(Failure.class);
        }
        assertThatThrownBy(()->OntologyValues.canonical("NUMBER","1e999")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyValues.canonical("TEXT"," ")).isInstanceOf(Failure.class);
    }
    @Test void samplingRequiresConsentVersionAndKnownNonSensitiveSupportedColumns(){
        var e=entry(List.of());assertThat(OntologyValues.columns(e,new Lookup("APP","REGIONS",1,"CODE","LABEL",true))).hasSize(2);
        for(var r:List.of(new Lookup("APP","REGIONS",1,"CODE","LABEL",false),new Lookup("APP","REGIONS",2,"CODE","LABEL",true),new Lookup("APP","REGIONS",1,"EMAIL","LABEL",true),new Lookup("APP","REGIONS",1,"MISSING","LABEL",true)))
            assertThatThrownBy(()->OntologyValues.columns(e,r)).isInstanceOf(Failure.class);
        assertThat(OntologyValues.type("DATE")).isEmpty();assertThat(OntologyValues.type("CLOB")).isEmpty();
    }
    @Test void previewDropsNullPrivateAndTruncatedValuesWithoutInventingCodes(){
        var rows=new ArrayList<List<String>>();rows.add(List.of("EU","Europe"));rows.add(List.of("EU","Europe"));rows.add(Arrays.asList(null,"No code"));rows.add(List.of("x".repeat(201),"Too long"));rows.add(List.of("person@sample.test","Person"));rows.add(List.of("US","x".repeat(201)));
        var p=OntologyValues.preview("TEXT",rows,"now");assertThat(p.scanned()).isEqualTo(6);assertThat(p.skipped()).isEqualTo(4);assertThat(p.items()).containsExactly(new Candidate("EU","Europe"));
        assertThat(OntologyValues.preview("TEXT",List.of(),"now").items()).isEmpty();
        assertThatThrownBy(()->OntologyValues.preview("TEXT",Collections.nCopies(21,List.of("EU","Europe")),"now")).isInstanceOf(Failure.class);
    }
    @Test void aliasesUseTermBoundariesAndKoreanParticlesNotSubstringCodes(){
        for(String q:List.of("EU 재고","eu?","EU의 재고","유럽에 있는 재고","유럽으로","Europe inventory"))assertThat(OntologyValues.matching(entry(List.of(europe())),q)).hasSize(1);
        for(String q:List.of("EUR 매출","NEU","유럽연합","Europeans"))assertThat(OntologyValues.matching(entry(List.of(europe())),q)).isEmpty();
        assertThat(OntologyValues.matching(entry("REGIONS","DRAFT",List.of(europe())),"EU")).isEmpty();
        assertThat(OntologyValues.mentions("Area [West]","Area [West]")).isTrue();
    }
    @Test void conflictingSameTermsRequireClarificationButDistinctTermsAreRetained(){
        var a=entry(List.of(europe()));var b=entry("DIVISIONS","APPROVED",List.of(binding("CODE","TEXT","WEST","Western",List.of("EU"))));
        assertThatThrownBy(()->OntologyValues.unambiguous(List.of(a,b),"EU inventory")).isInstanceOf(Failure.class);
        OntologyValues.unambiguous(List.of(a,entry("DIVISIONS","DRAFT",b.document().meaning().valueMappings())),"EU inventory");
        OntologyValues.unambiguous(List.of(entry(List.of(europe(),binding("CODE","TEXT","APAC","Asia Pacific",List.of("아시아"))))),"EU와 APAC");
    }
    @Test void rdfLinksCodeTypeAndColumnToSkosAndRemainsValidTurtle() throws Exception {
        var b=binding("CODE","TEXT","O'Reilly <x>","Quoted \" label",List.of("별칭"));var e=entry(List.of(b));
        var exported=OntologyRdf.export(e);var rdf=Rio.parse(new StringReader(exported.text()),"",RDFFormat.TURTLE);
        assertThat(rdf).hasSize(exported.triples().size());assertThat(exported.text()).contains("dbc:ValueMapping","skos:notation","skos:prefLabel","skos:altLabel","dbc:valueType \"TEXT\"","/column/CODE>","USER_DECLARED");
        assertThat(exported.text()).doesNotContain("owl:sameAs","SELECT ");
    }
    @Test void ordinaryAiEditsAndContextKeepMappings(){
        var e=entry(List.of(europe()));var m=Ontology.suggestion(e,"{\"concept\":\"Region\",\"description\":\"Regions\",\"columns\":{}}",json);
        assertThat(m.valueMappings()).isEqualTo(e.document().meaning().valueMappings());
        assertThat(OntologyContext.merge(e,List.of()).valueMappings()).isEqualTo(m.valueMappings());
        assertThat(OntologyContext.filtered(e,Map.of("REGIONS",e)).document().meaning().valueMappings()).isEqualTo(m.valueMappings());
    }
    @Test void sensitiveReclassificationRemovesBindingsFromExternalContextAndRequiresLocalCorrection(){
        var e=entry(List.of(europe()));var columns=new LinkedHashMap<>(e.document().meaning().columns());columns.put("CODE",new ColumnMeaning("","SENSITIVE"));
        var m=new Meaning("","",columns,Map.of(),e.document().meaning().valueMappings());
        var sensitive=new Entry(e.seq(),e.revision(),e.documentId(),e.state(),e.actor(),e.recordedAt(),new Document(1,e.document().source(),m,"USER",null));
        assertThatThrownBy(()->Ontology.validate(e.document().source(),m)).isInstanceOf(Failure.class);
        assertThat(OntologyContext.filtered(sensitive,Map.of("REGIONS",sensitive)).document().meaning().valueMappings()).isEmpty();
    }
    @Test void wizardMergeAlsoPreservesMappings(){
        var e=entry(List.of(europe()));var definition=new OntologyWizard.Definition("Code",List.of(),"","dimension","UNKNOWN","","","APP.P","now",2);
        var recommendation=new OntologyWizard.Recommendation("CODE","Region code",definition);
        var proposal=new OntologyWizard.Proposal("token","APP","REGIONS",1,List.of(recommendation),java.time.Instant.now().plusSeconds(60));
        var updated=OntologyWizard.merge(e,proposal,List.of(new OntologyWizard.Edit("CODE","Region code","Code",List.of(),"","dimension")));
        assertThat(updated.valueMappings()).isEqualTo(e.document().meaning().valueMappings());
    }
    @Test void ambiguousBindingsCannotReachPayloadOrAiTestSelection(){
        var e=entry(List.of(europe(),binding("CODE","TEXT","WEST","Western region",List.of("EU"))));var data=dataset(List.of(e));
        var search=OntologyInquiry.search(data,"EU","");var chosen=OntologyInquiry.chooseRoute(search,search.routes().getFirst().id());
        assertThatThrownBy(()->OntologyInquiry.payload(chosen,List.of(e),json)).isInstanceOf(Failure.class);
        var state=new SelectAiEvidence.State();state.dataset("APP",false,()->data);var found=state.search("APP","EU","");
        assertThatThrownBy(()->state.choose(found.id(),found.routes().getFirst().id(),json)).isInstanceOf(Failure.class);assertThat(state.selected()).isNull();
    }
    @Test void selectAiTestAndReviewerReceiveExplicitBindingsNotInventedFilters(){
        var e=entry(List.of(europe()));var state=new SelectAiEvidence.State();state.dataset("APP",false,()->dataset(List.of(e)));
        var found=state.search("APP","유럽","");var snapshot=state.choose(found.id(),found.routes().getFirst().id(),json);
        assertThat(SelectAiTest.prompt(SelectAiTest.Action.SQL,"유럽","ko",snapshot)).contains("approvedValueMappings","typed column equality","REGIONS","EU");
        var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"oci","model","v1");
        var outcome=new SelectAiTest.Outcome("p",SelectAiTest.Action.PROMPT,profile,"유럽",java.time.Instant.now(),1,"prompt",null,null,"complete",snapshot);
        assertThat(SelectAiReview.prompt(outcome,null,"ko")).contains("approvedValueMappings","without claiming row verification");
    }
    @Test void approvedMappingBeatsIncidentalCurrencyTextAndOnlySelectedApprovedValuesAreSent(){
        var e=entry(List.of(europe(),binding("CODE","TEXT","APAC","Asia Pacific",List.of("아시아"))));
        var other=entry("EUR_SALES","APPROVED",List.of());var data=dataset(List.of(e,other));var search=OntologyInquiry.search(data,"EU","");
        assertThat(search.matches()).extracting(OntologyInquiry.Match::table).containsExactly("REGIONS");
        var selected=OntologyInquiry.chooseRoute(search,search.routes().getFirst().id());String payload=OntologyInquiry.payload(selected,OntologyInquiry.selected(selected,data),json);
        assertThat(json.readTree(payload).path("approvedValueMappings")).hasSize(1);assertThat(payload).contains("Europe","EQ","valueMapping").doesNotContain("APAC","Asia Pacific");
        var metadata=OntologyInquiry.choose(search,List.of(search.evidence().stream().filter(v->v.kind().equals("METADATA")).findFirst().orElseThrow().id()));
        assertThat(json.readTree(OntologyInquiry.payload(metadata,List.of(e),json)).path("approvedValueMappings")).isEmpty();
        var draft=entry("REGIONS","DRAFT",List.of(europe()));var draftSearch=OntologyInquiry.search(dataset(List.of(draft)),"REGIONS","");
        assertThat(OntologyInquiry.payload(draftSearch,List.of(draft),json)).doesNotContain("Europe");
    }
    @Test void serviceGuardsDoNotOpenAPool(){
        var source=new com.dbcompanion.common.db.SessionDataSource();var service=new OntologyValuesService(source,null,null);
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var session=new com.dbcompanion.common.db.PoolSession(pool,"LOW",()->{})){
            session.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));
            for(var r:List.of(new Lookup("OTHER","T",1,"C","N",true),new Lookup("APP","T",1,"C","N",false)))assertThatThrownBy(()->service.lookup(session,r)).isInstanceOf(Failure.class);
            assertThat(pool.getHikariPoolMXBean()).isNull();
        }
    }
}
