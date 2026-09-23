package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyRelations.*;
import com.dbcompanion.service.OntologyRdf;
import java.util.*;
import java.io.StringReader;
import org.eclipse.rdf4j.rio.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyRelationsTest {
    Key key(String type,String... columns){return new Key("K_"+type,type,List.of(columns),null,null,List.of(),"ENABLED","VALIDATED");}
    Entry entry(String name,List<ColumnInfo> columns,List<Key> keys,Map<String,ColumnMeaning> meaning){
        var snapshot=new Snapshot("DB","APP",name,"",columns,keys,"now");var initial=Ontology.initial(snapshot);var all=new LinkedHashMap<>(initial.columns());all.putAll(meaning);
        return new Entry("1",1,UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"DRAFT","APP","now",new Document(1,snapshot,new Meaning(name,"",all,initial.relations()),"CATALOG",null));
    }
    ColumnInfo col(String name,String type){return new ColumnInfo(1,name,type,"N","");}
    ColumnMeaning meaning(String label,String... aliases){return new ColumnMeaning("","UNKNOWN",new OntologyWizard.Definition(label,List.of(aliases),"","","MATCH","reason","","APP.P","now",10));}
    Entry customer(){return entry("CUSTOMER",List.of(col("CUSTOMER_ID","NUMBER(10)")),List.of(key("P","CUSTOMER_ID")),Map.of("CUSTOMER_ID",meaning("고객 식별번호","구매자 번호")));}
    Entry order(){return entry("ORDERS",List.of(col("BUYER_NO","NUMBER")),List.of(),Map.of("BUYER_NO",meaning("구매자 번호")));}
    Analysis analyze(Entry... entries){return OntologyRelations.analyze("DB","APP",List.of(entries),"now");}
    Review request(Entry source,Entry target,String id,String status){return new Review("APP",source.document().source().table(),source.documentId(),source.revision(),target.document().source().table(),target.documentId(),target.revision(),List.of("BUYER_NO"),List.of("CUSTOMER_ID"),id,"고객을 참조한다","활성 고객 범위만 검토",status);}
    @Test void differentNamesMatchSavedAliasesNotJustColumnNames(){
        var result=analyze(order(),customer());assertThat(result.tables()).hasSize(2);assertThat(result.relations()).hasSize(1);
        var r=result.relations().getFirst();assertThat(r.sourceColumns()).containsExactly("BUYER_NO");assertThat(r.targetColumns()).containsExactly("CUSTOMER_ID");
        assertThat(r.status()).isEqualTo("CANDIDATE");assertThat(r.evidence()).contains("TERM","TARGET_KEY","COMPATIBLE_TYPE").doesNotContain("NAME");
    }
    @Test void matchingShortDefinitionsWorkWithoutLabelsAndAreStillCandidates(){
        var a=entry("A",List.of(col("BUYER_NO","NUMBER")),List.of(),Map.of("BUYER_NO",new ColumnMeaning("고객 식별번호","UNKNOWN")));
        var b=entry("B",List.of(col("CUSTOMER_ID","NUMBER")),List.of(key("U","CUSTOMER_ID")),Map.of("CUSTOMER_ID",new ColumnMeaning("고객 식별번호","UNKNOWN")));
        assertThat(analyze(a,b).relations().getFirst().evidence()).contains("DEFINITION");
    }
    @Test void genericNamesTypesPrivacyAndUnverifiedKeysDoNotProduceCandidates(){
        var a=entry("A",List.of(col("ID","NUMBER"),col("CUSTOMER_ID","DATE"),col("EMAIL","VARCHAR2(100)")),List.of(),Map.of());
        var b=entry("B",List.of(col("ID","NUMBER"),col("EMAIL","VARCHAR2(100)")),List.of(key("P","ID"),key("U","EMAIL")),Map.of());
        assertThat(analyze(a,b,customer()).relations()).isEmpty();
        assertThat(OntologyRelations.compatible("VARCHAR2(20)","NVARCHAR2(40)")).isTrue();
        assertThat(OntologyRelations.compatible("CLOB","CLOB")).isFalse();
        var old=new Key("OLD","P",List.of("CUSTOMER_ID"),null,null,List.of(),"DISABLED","NOT VALIDATED");
        assertThat(analyze(order(),entry("OLD",customer().document().source().columns(),List.of(old),customer().document().meaning().columns())).relations()).isEmpty();
    }
    @Test void compositeKeysMustMatchCompletelyAndAmbiguousMatchesAreNotChosen(){
        var target=entry("PARENT",List.of(col("TENANT_ID","NUMBER"),col("CUSTOMER_ID","NUMBER")),List.of(key("P","TENANT_ID","CUSTOMER_ID")),Map.of());
        var partial=entry("PARTIAL",List.of(col("CUSTOMER_ID","NUMBER")),List.of(),Map.of());
        var complete=entry("COMPLETE",target.document().source().columns(),List.of(),Map.of());
        assertThat(analyze(partial,target).relations()).isEmpty();
        var r=analyze(complete,target).relations().getFirst();assertThat(r.sourceColumns()).containsExactly("TENANT_ID","CUSTOMER_ID");
        var ambiguous=entry("AMBIGUOUS",List.of(col("BUYER_NO","NUMBER"),col("SECOND_NO","NUMBER")),List.of(),Map.of("BUYER_NO",meaning("구매자 번호"),"SECOND_NO",meaning("구매자 번호")));
        assertThat(analyze(ambiguous,customer()).relations()).isEmpty();
    }
    @Test void foreignKeyEvidenceIsKeptAndDoesNotDuplicateAHeuristicCandidate(){
        var fk=new Key("REAL_FK","R",List.of("BUYER_NO"),"APP","CUSTOMER",List.of("CUSTOMER_ID"),"DISABLED","NOT VALIDATED");
        var source=entry("ORDERS",order().document().source().columns(),List.of(fk),order().document().meaning().columns());
        var result=analyze(source,customer());assertThat(result.relations()).hasSize(1);assertThat(result.relations().getFirst().status()).isEqualTo("FK");
        assertThatThrownBy(()->OntologyRelations.review(request(source,customer(),"","APPROVED"),source,customer(),result,"APP","now")).isInstanceOf(Failure.class);
    }
    @Test void reviewCreatesImmutableVersionEvidenceAndPreservesOriginalDocument(){
        var source=order();var target=customer();var data=analyze(source,target);var candidate=data.relations().getFirst();
        var link=OntologyRelations.review(request(source,target,candidate.id(),"APPROVED"),source,target,data,"REVIEWER","2026-09-20T10:00:00Z");
        assertThat(link.actor()).isEqualTo("REVIEWER");assertThat(link.sourceRevision()).isEqualTo(2);assertThat(link.targetRevision()).isEqualTo(1);assertThat(link.origin()).isEqualTo("RULE");
        var merged=OntologyRelations.merge(source,link);assertThat(source.document().links()).isEmpty();assertThat(merged.source()).isSameAs(source.document().source());assertThat(merged.meaning()).isSameAs(source.document().meaning());
        var reviewed=new Entry("2",2,source.documentId(),"DRAFT","REVIEWER","now",merged);
        assertThat(analyze(reviewed,target).relations()).hasSize(1);assertThat(analyze(reviewed,target).relations().getFirst().status()).isEqualTo("APPROVED");
        var changed=new Entry("3",3,source.documentId(),"DRAFT","APP","later",merged);
        assertThat(analyze(changed,target).relations().getFirst().status()).isEqualTo("APPROVED");
        var json=new JsonMapper();assertThat(Ontology.parse(Ontology.json(merged,json),json)).isEqualTo(merged);
    }
    @Test void manualMappingsNeedNeitherSameNamesNorAKeyAndSelfReferenceIsSupported(){
        var source=order();var target=entry("NO_KEY",customer().document().source().columns(),List.of(),Map.of());var data=analyze(source,target);
        assertThat(data.relations()).isEmpty();var link=OntologyRelations.review(request(source,target,"","APPROVED"),source,target,data,"APP","now");assertThat(link.origin()).isEqualTo("USER");
        var self=entry("SELF",List.of(col("BUYER_NO","NUMBER"),col("CUSTOMER_ID","NUMBER")),List.of(),Map.of());
        var r=OntologyRelations.review(request(self,self,"","APPROVED"),self,self,analyze(self),"APP","now");assertThat(r.targetRevision()).isEqualTo(2);
    }
    @Test void staleTamperedUnknownColumnsAndMismatchedSchemaAreRejected(){
        var source=order();var target=customer();var data=analyze(source,target);
        var changed=new Entry("2",2,target.documentId(),"DRAFT","APP","now",target.document());
        assertThatThrownBy(()->OntologyRelations.review(request(source,target,"","APPROVED"),source,changed,data,"APP","now")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyRelations.review(request(source,target,"forged","APPROVED"),source,target,data,"APP","now")).isInstanceOf(Failure.class);
        var invalid=new Review("APP","ORDERS",source.documentId(),1,"CUSTOMER",target.documentId(),1,List.of("MISSING"),List.of("CUSTOMER_ID"),"","name","","APPROVED");
        assertThatThrownBy(()->OntologyRelations.review(invalid,source,target,data,"APP","now")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyRelations.analyze("DB","OTHER",List.of(source),"now")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyRelations.analyze("DB","APP",Collections.nCopies(501,source),"now")).isInstanceOf(Failure.class);
    }
    @Test void rejectionSuppressesTheSameCandidateWithoutDroppingTheReview(){
        var source=order();var target=customer();var data=analyze(source,target);var r=data.relations().getFirst();
        var link=OntologyRelations.review(request(source,target,r.id(),"REJECTED"),source,target,data,"APP","now");
        var reviewed=new Entry("2",2,source.documentId(),"DRAFT","APP","now",OntologyRelations.merge(source,link));
        assertThat(analyze(reviewed,target).relations()).hasSize(1);assertThat(analyze(reviewed,target).relations().getFirst().status()).isEqualTo("REJECTED");
    }
    @Test void rdfStoresReviewNotInventedForeignKeyOrExecutablePredicate() throws Exception {
        var source=order();var target=customer();var data=analyze(source,target);var link=OntologyRelations.review(request(source,target,"","APPROVED"),source,target,data,"APP","now");
        var e=new Entry("2",2,source.documentId(),"DRAFT","APP","now",OntologyRelations.merge(source,link));
        var export=OntologyRdf.export(e);var model=Rio.parse(new StringReader(export.text()),"",RDFFormat.TURTLE);
        assertThat(model).hasSize(export.triples().size());assertThat(export.text()).contains("dbc:RelationshipReview","dbc:sourceColumnName","dbc:targetVersion","고객을 참조한다").doesNotContain("dbc:ForeignKeyMetadata");
    }
    @Test void oldJsonWithoutLinksStillParsesAndCachesInvalidate(){
        var mapper=new JsonMapper();var tree=mapper.valueToTree(order().document());((tools.jackson.databind.node.ObjectNode)tree).remove("links");
        assertThat(Ontology.parse(mapper.writeValueAsString(tree),mapper).links()).isEmpty();
        var state=new State();var count=new java.util.concurrent.atomic.AtomicInteger();java.util.function.Supplier<Analysis> load=()->{count.incrementAndGet();return analyze(order(),customer());};
        state.relationships("APP",load);state.relationships("APP",load);assertThat(count).hasValue(1);
        state.clear("APP");state.relationships("APP",load);assertThat(count).hasValue(2);
        state.catalog("APP",true,()->new Catalog("READY",false,List.of(),List.of(),"now"));state.relationships("APP",load);assertThat(count).hasValue(3);
    }
    @Test void writersPreserveLinksAndRepositoryUsesOnlySavedMetadata() throws Exception {
        var path=java.nio.file.Path.of("src/main/java/com/dbcompanion");
        assertThat(java.nio.file.Files.readString(path.resolve("service/OntologyService.java"))).contains("before.document().links()");
        assertThat(java.nio.file.Files.readString(path.resolve("service/OntologyWizardService.java"))).contains("e.document().links()");
        var code=java.nio.file.Files.readString(path.resolve("repository/OntologyRepository.java"));var method=code.substring(code.indexOf("public List<Entry> relationshipEntries"),code.indexOf("private Document payload"));
        assertThat(method).contains("WHERE OBJECT_OWNER=?","WHERE RN=1","FETCH FIRST 501 ROWS ONLY","clob.free()").doesNotContain("snapshot(","ALL_TAB_COLUMNS","DBMS_CLOUD_AI","INSERT INTO");
    }
    @Test void cacheHitDoesNotOpenAConnectionAndAnotherSelectedSchemaIsRejected(){
        var ds=new com.dbcompanion.common.db.SessionDataSource();var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);var mapper=new JsonMapper();
        var repository=new com.dbcompanion.repository.OntologyRepository(jdbc,mapper,new com.dbcompanion.repository.DatabaseRepository(jdbc),new com.dbcompanion.repository.TableStructureRepository(jdbc));
        var service=new com.dbcompanion.service.OntologyService(ds,repository,new com.dbcompanion.repository.AiAssistantRepository(jdbc),null,mapper);
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var session=new com.dbcompanion.common.db.PoolSession(pool,"LOW",()->{})){
            session.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));var cached=analyze(order(),customer());
            session.metadata().ontology().relationships("APP",()->cached);assertThat(service.relationships(session,"APP")).isSameAs(cached);assertThat(pool.getHikariPoolMXBean()).isNull();
            assertThatThrownBy(()->service.relationships(session,"OTHER")).isInstanceOf(Failure.class);
        }
    }
}
