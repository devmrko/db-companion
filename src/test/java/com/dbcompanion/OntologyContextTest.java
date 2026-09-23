package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyAnalysis.*;
import com.dbcompanion.service.*;
import java.io.StringReader;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.eclipse.rdf4j.rio.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyContextTest {
    final JsonMapper json=new JsonMapper();
    ColumnInfo col(String name){return new ColumnInfo(1,name,"NUMBER","N",name+" source");}
    Key fk(String name,String target,List<String> from,List<String> to){return new Key(name,"R",from,"APP",target,to,"DISABLED","NOT VALIDATED");}
    Entry entry(String table,List<Key> keys){
        var source=new Snapshot("DB","APP",table,"table source",List.of(col("ID"),col("TENANT"),col("EMAIL"),col("PRIVATE_FLAG")),keys,"2026-09-20");
        var original=Ontology.initial(source);var columns=new LinkedHashMap<>(original.columns());columns.put("PRIVATE_FLAG",new ColumnMeaning("do not send this meaning","SENSITIVE"));
        return new Entry("1",2,UUID.nameUUIDFromBytes(table.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"DRAFT","ACTOR_SECRET","2026-09-20",new Document(1,source,new Meaning("saved concept","saved description",columns,original.relations()),"USER","PROFILE_SECRET"));
    }
    Entry target(){return entry("ORDER",List.of(fk("FK_PARENT","PARENT",List.of("TENANT","ID"),List.of("TENANT","ID"))));}
    GraphTable graph(Entry e){return new GraphTable(e.document().source().table(),e.revision(),e.documentId(),e.state(),e.recordedAt(),e.document().source().capturedAt(),e.document().source().keys());}
    Bundle bundle(){return OntologyContext.bundle(target(),List.of(entry("PARENT",List.of())),json);}
    Recommendation recommendation(String field,String name){return new Recommendation(field,name,"주문 고객 관계","복합 FK","업무 담당자 확인");}
    String output(Recommendation... rows){return json.writeValueAsString(Map.of("suggestions",List.of(rows)));}
    @Test void findsOnlyDirectSameSchemaNeighborsInBothDirections(){
        var parent=entry("PARENT",List.of(fk("FK_GRAND","GRAND",List.of("ID"),List.of("ID"))));
        var child=entry("CHILD",List.of(fk("FK_ORDER","ORDER",List.of("ID"),List.of("ID"))));
        var g=new GraphData("APP",List.of(graph(target()),graph(parent),graph(child),graph(entry("GRAND",List.of())),graph(entry("UNRELATED",List.of()))),"now");
        assertThat(OntologyContext.neighbors(target(),g)).containsExactly("CHILD","PARENT");
        assertThatThrownBy(()->OntologyContext.neighbors(target(),new GraphData("OTHER",g.tables(),"now"))).isInstanceOf(Failure.class);
    }
    @Test void sensitiveColumnsAndKeysAreRemovedBeforeRdfRendering() throws Exception {
        var e=entry("ORDER",List.of(fk("FK_EMAIL","PARENT",List.of("EMAIL"),List.of("ID")),fk("FK_PRIVATE_TARGET","PARENT",List.of("ID"),List.of("PRIVATE_FLAG")),fk("FK_PARENT","PARENT",List.of("TENANT","ID"),List.of("TENANT","ID"))));
        var b=OntologyContext.bundle(e,List.of(entry("PARENT",List.of())),json);
        assertThat(b.context().relations()).containsExactly("FK_PARENT");
        var graphs=json.readTree(b.payload()).path("graphs");
        for(var item:graphs){String rdf=item.path("rdf").asString();assertThat(rdf).doesNotContain("EMAIL","PRIVATE_FLAG","do not send","ACTOR_SECRET","PROFILE_SECRET");assertThat(Rio.parse(new StringReader(rdf),"urn:test:",RDFFormat.TURTLE)).isNotEmpty();}
        assertThat(b.context().omitted()).contains("ORDER / FK_EMAIL","ORDER / FK_PRIVATE_TARGET");
    }
    @Test void compactRdfHasSameTypedTriplesExceptPrivateOperationalFields() throws Exception {
        var e=target();var original=OntologyRdf.export(e);
        var model=Rio.parse(new StringReader(OntologyContext.compact(original)),"urn:test:",RDFFormat.TURTLE);
        var full=Rio.parse(new StringReader(original.text()),"urn:test:",RDFFormat.TURTLE);
        full.removeIf(s->Set.of("urn:dbcompanion:ontology:actor","urn:dbcompanion:ontology:profile").contains(s.getPredicate().stringValue()));
        assertThat(model).isEqualTo(full);
        assertThat(OntologyContext.compact(original)).contains("DISABLED","NOT VALIDATED","dbc:position 2","dbc:sourceComment","saved description");
    }
    @Test void missingAndCrossSchemaReferencesAreVisibleButNotGuessed(){
        var e=entry("ORDER",List.of(fk("MISSING","MISSING_TABLE",List.of("ID"),List.of("ID")),new Key("CROSS","R",List.of("ID"),"OTHER","T",List.of("ID"),"ENABLED","VALIDATED")));
        var b=OntologyContext.bundle(e,List.of(),json);assertThat(b.context().relations()).isEmpty();assertThat(b.context().omitted()).containsExactly("ORDER / CROSS","ORDER / MISSING");
        assertThat(b.payload()).doesNotContain("MISSING_TABLE");
    }
    @Test void doesNotMergeDifferentDatabasesOrDuplicateDocuments(){
        var parent=entry("PARENT",List.of());var s=parent.document().source();var bad=new Entry(parent.seq(),parent.revision(),parent.documentId(),parent.state(),parent.actor(),parent.recordedAt(),new Document(1,new Snapshot("OTHER_DB",s.schema(),s.table(),s.comment(),s.columns(),s.keys(),s.capturedAt()),parent.document().meaning(),"USER",null));
        assertThatThrownBy(()->OntologyContext.bundle(target(),List.of(bad),json)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyContext.bundle(target(),List.of(parent,parent),json)).isInstanceOf(Failure.class);
    }
    @Test void excessiveContextStopsRatherThanTruncating(){
        var refs=new ArrayList<Entry>();for(int i=0;i<10;i++)refs.add(entry("N"+i,List.of()));
        assertThatThrownBy(()->OntologyContext.bundle(target(),refs,json)).isInstanceOf(Failure.class);
        var e=target();var m=e.document().meaning();var c=new LinkedHashMap<>(m.columns());c.put("ID",new ColumnMeaning("x".repeat(65_000),"UNKNOWN"));
        var huge=new Entry(e.seq(),e.revision(),e.documentId(),e.state(),e.actor(),e.recordedAt(),new Document(1,e.document().source(),new Meaning(m.concept(),m.description(),c,m.relations()),"USER",null));
        assertThatThrownBy(()->OntologyContext.bundle(huge,List.of(),json)).isInstanceOf(Failure.class);
    }
    @Test void referencesDetectRevisionDocumentStateAndDatabaseChanges(){
        var e=target();var r=OntologyContext.reference(e);assertThat(OntologyContext.same(r,e)).isTrue();assertThat(OntologyContext.same(r,null)).isFalse();
        assertThat(OntologyContext.same(r,new Entry("2",3,e.documentId(),e.state(),e.actor(),e.recordedAt(),e.document()))).isFalse();
        assertThat(OntologyContext.same(r,new Entry("2",2,e.documentId(),"APPROVED",e.actor(),e.recordedAt(),e.document()))).isFalse();
    }
    @Test void parserOnlyAcceptsKnownTargetFieldsAndSeparatesNotes(){
        var value=OntologyContext.parse(output(recommendation("relation","FK_PARENT")),bundle().context(),json);
        assertThat(value).containsExactly(recommendation("relation","FK_PARENT"));
        assertThat(OntologyContext.parse("{\"suggestions\":[]}",bundle().context(),json)).isEmpty();
        for(var bad:List.of(recommendation("column","ID"),recommendation("relation","INVENTED"),recommendation("concept","PARENT"),new Recommendation("description","","x".repeat(81),"",""),new Recommendation("description","","코드로 보이며 역할로 추정됨","","")))
            assertThatThrownBy(()->OntologyContext.parse(output(bad),bundle().context(),json)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyContext.parse(output(recommendation("concept",""),recommendation("concept","")),bundle().context(),json)).isInstanceOf(Failure.class);
        for(String bad:List.of("```json\n{}\n```","{\"suggestions\":[],\"sql\":\"DROP\"}","{\"suggestions\":[{\"field\":\"concept\"}]}"))assertThatThrownBy(()->OntologyContext.parse(bad,bundle().context(),json)).isInstanceOf(Failure.class);
    }
    @Test void selectedEditsKeepUnselectedMeaningsAndAllColumns(){
        var recs=List.of(recommendation("concept",""),recommendation("relation","FK_PARENT"));
        var chosen=OntologyContext.selected(recs,List.of(new Edit("relation","FK_PARENT","고객별 주문")));
        var result=OntologyContext.merge(target(),chosen);assertThat(result.concept()).isEqualTo("saved concept");assertThat(result.columns()).isEqualTo(target().document().meaning().columns());
        assertThat(result.relations()).containsEntry("FK_PARENT","고객별 주문");assertThat(chosen.getFirst().reason()).isEqualTo("복합 FK");
        for(var edits:List.of(List.<Edit>of(),List.of(new Edit("relation","UNSEEN","x")),List.of(new Edit("concept","","x"),new Edit("concept","","y"))))
            assertThatThrownBy(()->OntologyContext.selected(recs,edits)).isInstanceOf(Failure.class);
    }
    @Test void evidenceJsonIsOptionalAndVersionLinkedInRdf() throws Exception {
        var e=target();var b=bundle();var accepted=List.of(recommendation("relation","FK_PARENT"));
        var evidence=new Evidence(b.context().sources(),accepted,Instant.EPOCH.toString());
        var doc=new Document(1,e.document().source(),OntologyContext.merge(e,accepted),"AI_RDF_REVIEWED","APP.AI",evidence);
        assertThat(Ontology.parse(Ontology.json(doc,json),json)).isEqualTo(doc);
        assertThat(Ontology.parse(Ontology.json(e.document(),json).replace(",\"analysis\":null",""),json).analysis()).isNull();
        var saved=new Entry("2",3,e.documentId(),"DRAFT","APP","now",doc);String rdf=OntologyRdf.render(saved);
        assertThat(rdf).contains("dbc:basedOn <urn:uuid:"+e.documentId()+"/revision/2>","dbc:ReviewedSuggestion","고객 관계");
        assertThat(Rio.parse(new StringReader(rdf),"urn:test:",RDFFormat.TURTLE)).isNotEmpty();
    }
    @Test void sampleContextAddsOnlySelectedKeysAndNoRelatedRows(){
        var e=target();var cols=OntologyWizard.select(e,List.of("ID","TENANT"),true,10);
        var sample=OntologyWizard.sanitize(e,cols,List.of(List.of("1","2")),Instant.EPOCH);
        String payload=OntologyWizard.payload(sample,e,json);
        assertThat(payload).contains("tableContext","saved description","FK_PARENT","NOT VALIDATED","LIMITED_ROWS_NOT_RANDOM_NOT_STATISTICS").doesNotContain("EMAIL","PRIVATE_FLAG","do not send");
        var partial=OntologyWizard.sanitize(e,List.of(cols.getFirst()),List.of(List.of("1")),Instant.EPOCH);
        assertThat(OntologyWizard.payload(partial,e,json)).doesNotContain("FK_PARENT");
    }
    @Test void promptUsesSavedRdfAndNeverPresentsDraftsAsBusinessTruth(){
        var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","AI"),"oci","model","hash");var state=new AiAssistant.State();state.select(profile.selection());
        var preview=state.prepare("APP",profile,"ORDER",bundle().payload(),false,"ko",Instant.now(),"ontology");
        var draft=state.consume(preview.token(),true,"APP",Instant.now(),"ontology");String prompt=OntologyContext.prompt(draft);
        assertThat(prompt).contains("DRAFT is unapproved","APPROVED is human-reviewed","Korean","Do not rewrite column definitions","untrusted data","SAVED_RDF_ONLY");
        assertThat(prompt).contains("SKOS prefLabel and altLabel", "do not establish new facts");
    }
    @Test void serviceGuardsEveryReferenceBeforeCallAndSaveAndDoesNotSample() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/dbcompanion/service/OntologyService.java"));
        assertThat(source).contains("verifyContext(s,preview.context())","verifyContext(s,proposal.context())","profile.equals(ai.profile","OntologyContext.selected","\"AI_RDF_REVIEWED\"","new Evidence","assistant().discard(token)").doesNotContain("samples.sample(","Ontology.aiSource(","Ontology.suggestion(");
    }
}
