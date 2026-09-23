package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.service.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyScopeTest {
    final JsonMapper json=new JsonMapper();final OntologyRelationsTest fixture=new OntologyRelationsTest();
    @Test void profileImportAccountsForMissingAndForeignObjectsWithoutExpandingSelection(){
        var v=OntologyScope.fromProfile("""
            [{"owner":"app","name":"a"},{"owner":"APP","name":"MISSING"},{"owner":"OTHER","name":"B"},{"owner":"app","name":"a"}]
            ""","APP",List.of("A","B"),json);
        assertThat(v.tables()).containsExactly("A");assertThat(v.missing()).containsExactly("MISSING");assertThat(v.outside()).containsExactly("OTHER.B");assertThat(v.wholeSchema()).isFalse();
    }
    @Test void quotedIdentifiersAndOwnerOnlyScopesAreExactAndExplicit(){
        String input=json.writeValueAsString(List.of(Map.of("owner","\"Mixed Owner\"","name","\"Mixed \"\"Name\"")));var available=List.of("Mixed \"Name","MIXED");
        assertThat(OntologyScope.fromProfile(input,"Mixed Owner",available,json).tables()).containsExactly("Mixed \"Name");
        var all=OntologyScope.fromProfile("[{\"owner\":\"app\"}]","APP",List.of("B","A"),json);
        assertThat(all.tables()).containsExactly("A","B");assertThat(all.wholeSchema()).isTrue();
    }
    @Test void invalidOrAbsentProfileNeverFallsBackToAllTables(){
        for(String input:Arrays.asList(null,"","null","[]","{}","[{\"name\":\"A\"}]","[{\"owner\":\"APP\",\"name\":null}]","[{\"owner\":\"APP\",\"name\":\"\\\"bad\"}]"))
            assertThatThrownBy(()->OntologyScope.fromProfile(input,"APP",List.of("A"),json)).isInstanceOf(Failure.class);
    }
    @Test void savedSelectionIsImmutableOrderedBoundedAndPreservesNames(){
        var input=new ArrayList<>(List.of("B","a\"b"));var v=new OntologyScope.Selection(1,"DB","APP","고객 분석",input);input.clear();
        assertThat(v.tables()).containsExactly("B","a\"b");assertThat(json.readValue(json.writeValueAsString(v),OntologyScope.Selection.class)).isEqualTo(v);
        assertThatThrownBy(()->OntologyScope.names(List.of())).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyScope.names(null)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyScope.names(List.of("A","A"))).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyScope.names(IntStream.range(0,501).mapToObj(i->"T"+i).toList())).isInstanceOf(Failure.class);
        assertThatThrownBy(()->new OntologyScope.Selection(1,"DB","APP"," ",List.of("A"))).isInstanceOf(Failure.class);
    }
    private String expand(String name,JsonNode data){
        if(name.startsWith("@"))return data.path("base").asString()+name.substring(1);
        int p=name.indexOf(':');if(p>0&&data.path("prefixes").has(name.substring(0,p)))return data.path("prefixes").path(name.substring(0,p)).asString()+name.substring(p+1);return name;
    }
    private List<OntologyRdf.Triple> decode(Map<String,Object> map){
        var data=json.valueToTree(map);var rows=new ArrayList<OntologyRdf.Triple>();
        for(var subject:data.path("nodes").properties())for(var predicate:subject.getValue().properties())for(var value:predicate.getValue()){
            OntologyRdf.Term term=value.isString()?new OntologyRdf.Term("LITERAL",value.asString()):
                value.has("iri")?new OntologyRdf.Term("IRI",expand(value.path("iri").asString(),data)):
                value.has("integer")?new OntologyRdf.Term("INTEGER",value.path("integer").asString()):new OntologyRdf.Term("LITERAL",data.path("texts").get(value.path("text").asInt()).asString());
            rows.add(new OntologyRdf.Triple(expand(subject.getKey(),data),expand(predicate.getKey(),data),term));
        }return rows;
    }
    @Test void compactRdfLosslesslyPreservesPredicateProvenanceTypesCompositeOrderAndUnicode(){
        String comment="同じ説明 / 같은 설명 \" \\ \n 😀 ".repeat(20);
        var e=fixture.entry("長い\"表",List.of(new ColumnInfo(1,"ID","NUMBER","N",comment),new ColumnInfo(2,"CODE","VARCHAR2(30)","Y",comment)),List.of(new Key("PK","P",List.of("ID","CODE"),null,null,List.of(),"ENABLED","VALIDATED")),Map.of());
        var rdf=OntologyRdf.export(e);var packed=OntologyDiscoveryRdf.encode(rdf);
        assertThat(decode(packed)).containsExactlyInAnyOrderElementsOf(rdf.triples().stream().filter(OntologyDiscoveryRdf::included).toList());
        assertThat(json.writeValueAsString(packed)).hasSizeLessThan(json.writeValueAsString(OntologyContext.compact(rdf)).length());
        assertThat(json.valueToTree(packed).path("texts")).hasSize(1);
        assertThat(json.writeValueAsString(packed)).contains("dbc:sourceComment","rdfs:comment");
    }
    @Test void skosColumnDefinitionsAndDifferentCommentsAreNotMerged(){
        var entry=fixture.customer();var source=entry.document().source();var cm=new LinkedHashMap<>(entry.document().meaning().columns());
        String column=source.columns().getFirst().name();var def=new OntologyWizard.Definition("고객 키",List.of("고객 식별자"),"","IDENTIFIER","SUPPORTED","근거","확인 필요","P","now",3);
        cm.put(column,new ColumnMeaning("reviewed meaning not source comment","UNKNOWN",def));
        var document=new Document(1,source,new Meaning("고객","reviewed table",cm,entry.document().meaning().relations()),"USER",null);
        var rdf=OntologyRdf.export(new Entry("1",1,entry.documentId(),"DRAFT","APP","now",document));
        assertThat(decode(OntologyDiscoveryRdf.encode(rdf))).containsExactlyInAnyOrderElementsOf(rdf.triples().stream().filter(OntologyDiscoveryRdf::included).toList());
    }
    Entry longEntry(String table,int length){return fixture.entry(table,List.of(new ColumnInfo(1,"ID","NUMBER","N","x".repeat(length))),List.of(),Map.of());}
    @Test void budgetStillRejectsOversizedTablesButAllowsMoreThanTwelveCalls(){
        var huge=OntologyDiscovery.estimate(List.of(longEntry("HUGE",30000),fixture.customer()),json);
        assertThat(huge.budget().reason()).isEqualTo("TABLE_SIZE");assertThat(huge.batches()).isEmpty();assertThat(huge.budget().sizes()).hasSize(2);
        var calls=OntologyDiscovery.estimate(IntStream.range(0,6).mapToObj(i->longEntry("T"+i,16000)).toList(),json);
        assertThat(calls.budget().reason()).isEmpty();assertThat(calls.budget().calls()).isEqualTo(15);assertThat(calls.batches()).hasSize(15);
    }
    @Test void budgetMatchesExactlySerializedInputAndCoversEveryPair(){
        var input=IntStream.range(0,5).mapToObj(i->longEntry("T"+i,16000)).toList();var estimate=OntologyDiscovery.estimate(input,json);
        assertThat(estimate.budget().reason()).isEmpty();assertThat(estimate.batches()).hasSize(10);
        assertThat(estimate.budget().transmissionCharacters()).isEqualTo(estimate.batches().stream().mapToLong(b->b.source().length()).sum());
        for(int i=0;i<5;i++)for(int j=i+1;j<5;j++){var pair=List.of("T"+i,"T"+j);assertThat(estimate.batches()).anyMatch(b->b.tables().containsAll(pair));}
    }
    @Test void scopedSuggestionsAppearInFullCatalogButRejectChangedSelectedVersions(){
        var old=new OntologyPipelineTest();var entries=List.of(old.orders(),old.customer());var batch=OntologyDiscovery.batches(entries,json).getFirst();var state=new OntologyPipeline.State();
        var plan=new OntologyDiscovery.Plan("t","APP",old.profile,List.of(batch),2,Instant.now().plusSeconds(60));state.prepare(plan,entries.stream().map(OntologyContext::reference).toList());
        state.authorize("t",0,true,"APP",Instant.now());state.begin("t",0,true,"APP",Instant.now());
        state.finish(OntologyDiscovery.parse(old.response("ORDERS","BUYER_NO"),batch,old.data(entries),old.profile,json),true);
        var extra=longEntry("OTHER",30);assertThat(state.augment(old.data(List.of(entries.getFirst(),entries.getLast(),extra))).relations()).anyMatch(r->r.origin().equals("AI"));
        var changed=new Entry("2",2,entries.getFirst().documentId(),"DRAFT","APP","now",entries.getFirst().document());
        assertThat(state.augment(old.data(List.of(changed,entries.getLast(),extra))).relations()).noneMatch(r->r.origin().equals("AI"));
        assertThat(state.augment(old.data(List.of(entries.getFirst(),extra))).relations()).noneMatch(r->r.origin().equals("AI"));
    }
}
