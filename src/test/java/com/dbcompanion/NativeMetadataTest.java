package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.NativeMetadata.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativeMetadataTest {
    @Test void scopeAndConfirmationFailBeforeOpeningADatabaseConnection(){
        var f=new OntologyReadCacheTest();var service=new com.dbcompanion.service.NativeMetadataService(f.source,null,null);
        try(var session=f.session()){
            assertThatThrownBy(()->service.install(session,"APP",false)).isInstanceOf(Ontology.Failure.class);
            assertThatThrownBy(()->service.capture(session,"APP","A",false)).isInstanceOf(Ontology.Failure.class);
            assertThatThrownBy(()->service.preview(session,"APP",null)).isInstanceOf(Ontology.Failure.class);
            assertThatThrownBy(()->service.status(session,"OTHER")).isInstanceOf(Ontology.Failure.class);
            session.metadata().selectSchema("OTHER");
            assertThatThrownBy(()->service.status(session,"OTHER")).isInstanceOf(Ontology.Failure.class);
            assertThatThrownBy(()->service.install(session,"OTHER",true)).isInstanceOf(Ontology.Failure.class);
            assertThatThrownBy(()->service.capture(session,"OTHER","A",true)).isInstanceOf(Ontology.Failure.class);
        }
        assertThat(f.connections).isZero();
    }
    @Test void dictionaryAndDisplayTypesAgreeWithoutChangingTheBaseType(){
        assertThat(NativeMetadata.baseType("VARCHAR2(32767 CHAR)")).isEqualTo("VARCHAR2");
        assertThat(NativeMetadata.baseType("TIMESTAMP(6) WITH TIME ZONE")).isEqualTo("TIMESTAMP WITH TIME ZONE");
        assertThat(NativeMetadata.baseType("NUMBER(10,2)")).isEqualTo("NUMBER");
        assertThat(NativeMetadata.baseType("NVARCHAR2(100)")).isNotEqualTo("VARCHAR2");
    }
    @Test void rdfCandidatePersistsWithoutApprovalOrLosingBusinessDefinitions(){
        var f=new OntologyRelationsTest();var a=f.entry("A",List.of(f.col("ENTITY_ID","NUMBER")),List.of(),Map.of());var b=f.entry("B",List.of(f.col("ENTITY_ID","NUMBER")),List.of(),Map.of());
        var id=OntologyRelations.id("A","APP","B",List.of("ENTITY_ID"),List.of("ENTITY_ID"));
        var r=new OntologyRelations.Relation(id,"A","APP","B",List.of("ENTITY_ID"),List.of("ENTITY_ID"),"CANDIDATE","RDF","","",List.of("ORACLE_RDF"),null,null);
        var result=OntologyRelations.candidates(a,Map.of("A",a,"B",b),List.of(r),"ACTOR","now");
        assertThat(result.meaning()).isEqualTo(a.document().meaning());assertThat(result.source()).isEqualTo(a.document().source());
        assertThat(result.links()).hasSize(1);assertThat(result.links().getFirst().status()).isEqualTo("CANDIDATE");assertThat(result.links().getFirst().origin()).isEqualTo("RDF");
        assertThat(Ontology.parse(Ontology.json(result,new tools.jackson.databind.json.JsonMapper()),new tools.jackson.databind.json.JsonMapper())).isEqualTo(result);
        var saved=new Ontology.Entry("2",2,a.documentId(),"DRAFT","ACTOR","now",result);
        assertThat(OntologyRelations.candidates(saved,Map.of("A",saved,"B",b),List.of(r),"ACTOR","later").links()).hasSize(1);
    }
    @Test void selectedNamesAndIrisAreStrict(){
        assertThat(NativeMetadata.names(List.of("B","A"),2)).containsExactly("A","B");
        for(var names:List.of(List.of("A"),List.of("A","A"),List.of("A","")))assertThatThrownBy(()->NativeMetadata.names(names,2)).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->NativeMetadata.names(Collections.nCopies(51,"A"),2)).isInstanceOf(Ontology.Failure.class);
        NativeMetadata.snapshot("urn:dbc:capture:"+"a".repeat(32));
        assertThatThrownBy(()->NativeMetadata.snapshot("bad'iri")).isInstanceOf(Ontology.Failure.class);
    }
    @Test void previewRequiresConfirmationSameSchemaTokenAndLifetimeAndIsSingleUse(){
        var now=Instant.now();var preview=new Preview("token","APP",List.of(),List.of(),0,now.plusSeconds(600));var pending=new Pending(preview,Map.of());var state=new State();state.prepare(pending);
        assertThatThrownBy(()->state.consume("APP","token",false,now)).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->state.consume("OTHER","token",true,now)).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->state.consume("APP","wrong",true,now)).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->state.consume("APP","token",true,now.plusSeconds(600))).isInstanceOf(Ontology.Failure.class);
        assertThat(state.consume("APP","token",true,now)).isSameAs(pending);
        assertThatThrownBy(()->state.consume("APP","token",true,now)).isInstanceOf(Ontology.Failure.class);
        state.prepare(pending);state.clear();assertThatThrownBy(()->state.consume("APP","token",true,now)).isInstanceOf(Ontology.Failure.class);
    }
}
