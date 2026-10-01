package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.NativeMetadata.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.NativeMetadataService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativeMetadataServiceTest {
    final OntologyReadCacheTest db=new OntologyReadCacheTest();
    final OntologyRelationsTest f=new OntologyRelationsTest();
    final Map<String,Ontology.Entry> entries=new LinkedHashMap<>();
    List<Snapshot> snapshots=List.of(new Snapshot("urn:dbc:capture:"+"a".repeat(32),"A","VIEW","now","APP"),new Snapshot("urn:dbc:capture:"+"b".repeat(32),"B","VIEW","now","APP"));
    int appends,sqlReads;
    final NativeMetadataRepository rdf=new NativeMetadataRepository(db.jdbc,db.json){
        @Override public String storage(){return "READY";}
        @Override public List<Snapshot> snapshots(){return snapshots;}
        @Override public List<Match> candidates(List<Snapshot> selected){sqlReads++;return List.of(new Match("A","B","ENTITY_ID","ENTITY_ID","VARCHAR2",snapshots.get(0).iri(),snapshots.get(1).iri()));}
    };
    final OntologyRepository catalog=new OntologyRepository(db.jdbc,db.json,new DatabaseRepository(db.jdbc),new TableStructureRepository(db.jdbc)){
        @Override public void require(String schema,String login){assertThat(schema).isEqualTo("APP");assertThat(login).isEqualTo("APP");}
        @Override public Ontology.Entry entry(String schema,String name,int revision){return entries.get(name);}
        @Override public Ontology.Entry append(String schema,String table,int expected,String state,Ontology.Document doc){
            assertThat(entries.get(table).revision()).isEqualTo(expected);assertThat(state).isEqualTo("DRAFT");appends++;
            var before=entries.get(table);var value=new Ontology.Entry("2",expected+1,before.documentId(),state,"APP","later",doc);entries.put(table,value);return value;
        }
    };
    final NativeMetadataService service=new NativeMetadataService(db.source,rdf,catalog);
    NativeMetadataServiceTest(){for(String name:List.of("A","B"))entries.put(name,f.entry(name,List.of(f.col("ENTITY_ID","VARCHAR2(100 CHAR)")),List.of(),Map.of()));}
    @Test void saveAppendsUnapprovedCandidateOnceAndNextPreviewSkipsDuplicate(){try(var s=db.session()){
        var before=entries.get("A");var preview=service.preview(s,"APP",List.of("B","A"));assertThat(sqlReads).isEqualTo(1);assertThat(appends).isZero();assertThat(preview.candidates()).hasSize(1);
        var ids=List.of(preview.candidates().getFirst().id());
        assertThatThrownBy(()->service.save(s,"APP",preview.token(),ids,false)).isInstanceOf(Ontology.Failure.class);assertThat(appends).isZero();
        assertThat(service.save(s,"APP",preview.token(),ids,true).saved()).isEqualTo(1);
        assertThat(entries.get("A").document().meaning()).isEqualTo(before.document().meaning());assertThat(entries.get("A").document().source()).isEqualTo(before.document().source());
        var link=entries.get("A").document().links().getFirst();assertThat(link.origin()).isEqualTo("RDF");assertThat(link.status()).isEqualTo("CANDIDATE");assertThat(link.evidence()).anyMatch(v->v.startsWith("SOURCE_RDF:"));
        assertThatThrownBy(()->service.save(s,"APP",preview.token(),ids,true)).isInstanceOf(Ontology.Failure.class);
        var again=service.preview(s,"APP",List.of("A","B"));assertThat(again.candidates()).isEmpty();assertThat(again.existing()).isEqualTo(1);assertThat(appends).isEqualTo(1);
    }}
    @Test void changedDefinitionOrSnapshotPreventsAppend(){try(var s=db.session()){
        var preview=service.preview(s,"APP",List.of("A","B"));var original=entries.get("B");entries.put("B",new Ontology.Entry("2",2,original.documentId(),"DRAFT","APP","changed",original.document()));
        assertThatThrownBy(()->service.save(s,"APP",preview.token(),List.of(preview.candidates().getFirst().id()),true)).isInstanceOf(Ontology.Failure.class);assertThat(appends).isZero();
        var latest=service.preview(s,"APP",List.of("A","B"));
        snapshots=List.of(new Snapshot("urn:dbc:capture:"+"c".repeat(32),"A","VIEW","new","APP"),snapshots.get(1));
        assertThatThrownBy(()->service.save(s,"APP",latest.token(),List.of(latest.candidates().getFirst().id()),true)).isInstanceOf(Ontology.Failure.class);assertThat(appends).isZero();
    }}
}
