package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyRelations.*;
import com.dbcompanion.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyPipelineTest {
    final OntologyRelationsTest fixture=new OntologyRelationsTest();final JsonMapper json=new JsonMapper();
    final AiAssistant.Profile profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"oci","m","v1");
    Entry orders(){return fixture.entry("ORDERS",List.of(fixture.col("ORDER_ID","NUMBER"),fixture.col("BUYER_NO","NUMBER")),List.of(fixture.key("P","ORDER_ID")),Map.of());}
    Entry customer(){return fixture.customer();}
    Analysis data(List<Entry> entries){return OntologyRelations.analyze("DB","APP",entries,"now");}
    String response(String source,String from){return json.writeValueAsString(Map.of("relations",List.of(Map.of("source",source,"target","CUSTOMER","sourceColumns",List.of(from),"targetColumns",List.of("CUSTOMER_ID"),"label","고객 참조","reason","구매자와 고객 식별 정의","uncertainty","실제 행은 검증하지 않음"))));}
    @Test void fullRdfBatchesIncludeEveryPairAndFilterSensitiveColumns(){
        var entries=new ArrayList<Entry>();
        for(int i=0;i<4;i++)entries.add(fixture.entry("TABLE"+i,List.of(new ColumnInfo(1,"KEY_COL","NUMBER","N","description "+"x".repeat(16000)),new ColumnInfo(2,"PASSWORD","VARCHAR2(80)","Y","PRIVATE_SECRET_MARKER")),List.of(fixture.key("P","KEY_COL")),Map.of()));
        var batches=OntologyDiscovery.batches(entries,json);assertThat(batches).hasSizeGreaterThan(1).hasSizeLessThanOrEqualTo(12);
        for(int i=0;i<entries.size();i++)for(int j=i+1;j<entries.size();j++){String a="TABLE"+i,b="TABLE"+j;assertThat(batches.stream().anyMatch(batch->batch.tables().containsAll(List.of(a,b)))).isTrue();}
        assertThat(batches).allSatisfy(b->{assertThat(b.source().length()).isLessThanOrEqualTo(AiAssistant.MAX_SOURCE);assertThat(b.source()).contains("rdf").doesNotContain("PRIVATE_SECRET_MARKER");});
    }
    @Test void oversizedRdfFailsWithoutTruncation(){
        var huge=fixture.entry("HUGE",List.of(new ColumnInfo(1,"KEY_COL","NUMBER","N","x".repeat(30000))),List.of(),Map.of());
        assertThatThrownBy(()->OntologyDiscovery.batches(List.of(huge,customer()),json)).isInstanceOf(Failure.class);
    }
    @Test void differentNamesBecomeUnconfirmedAiCandidatesWithServerEvidence(){
        var entries=List.of(orders(),customer());var batch=OntologyDiscovery.batches(entries,json).getFirst();
        var rows=OntologyDiscovery.parse(response("ORDERS","BUYER_NO"),batch,data(entries),profile,json);assertThat(rows).hasSize(1);
        var r=rows.getFirst();assertThat(r.status()).isEqualTo("CANDIDATE");assertThat(r.origin()).isEqualTo("AI");assertThat(r.evidence()).contains("AI_RDF","AI_PROFILE_NAME:P");
        var analysis=new Analysis("APP",data(entries).tables(),rows,"now");
        var req=new Review("APP","ORDERS",orders().documentId(),1,"CUSTOMER",customer().documentId(),1,r.sourceColumns(),r.targetColumns(),r.id(),r.label(),"","APPROVED");
        var link=OntologyRelations.review(req,orders(),customer(),analysis,"APP","now");OntologyRelations.validate(List.of(link));assertThat(link.origin()).isEqualTo("AI");
        assertThat(Ontology.parse(json.writeValueAsString(OntologyRelations.merge(orders(),link)),json).links()).containsExactly(link);
    }
    @Test void hallucinatedMissingSensitiveAndMalformedColumnsAreRejected(){
        var entries=List.of(orders(),customer());var batch=OntologyDiscovery.batches(entries,json).getFirst();
        for(String value:List.of(response("OTHER","BUYER_NO"),response("ORDERS","MISSING"),response("ORDERS","PASSWORD"),"```json\n{}\n```","{\"relations\":[],\"sql\":\"DROP TABLE X\"}"))
            assertThatThrownBy(()->OntologyDiscovery.parse(value,batch,data(entries),profile,json)).isInstanceOf(Failure.class);
        assertThat(OntologyDiscovery.parse("{\"relations\":[]}",batch,data(entries),profile,json)).isEmpty();
    }
    @Test void discoveryStateRequiresConsentOrderAndRejectsRetries(){
        var entries=List.of(orders(),customer());var batches=OntologyDiscovery.batches(entries,json);var state=new OntologyPipeline.State();var now=Instant.now();
        var plan=new OntologyDiscovery.Plan("token","APP",profile,batches,2,now.plusSeconds(60));state.prepare(plan,entries.stream().map(OntologyContext::reference).toList());
        assertThatThrownBy(()->state.begin("token",0,false,"APP",now)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->state.begin("token",1,true,"APP",now)).isInstanceOf(Failure.class);
        state.authorize("token",0,true,"APP",now);
        state.begin("token",0,true,"APP",now);assertThatThrownBy(()->state.begin("token",0,true,"APP",now)).isInstanceOf(Failure.class);
        var rows=OntologyDiscovery.parse(response("ORDERS","BUYER_NO"),batches.getFirst(),data(entries),profile,json);state.finish(rows,true);
        assertThat(state.augment(data(entries)).relations()).hasSize(1);assertThat(state.progress().done()).isTrue();
        assertThatThrownBy(()->state.begin("token",0,true,"APP",now)).isInstanceOf(Failure.class);
        var changed=new Entry("2",2,orders().documentId(),"DRAFT","APP","now",orders().document());
        assertThat(state.augment(data(List.of(changed,customer()))).relations()).isEmpty();state.clear();assertThat(state.augment(data(entries)).relations()).isEmpty();
    }
    @Test void graphUsesExplicitAliasesAndMappingsAndNeverAllProperties(){
        var entries=List.of(orders(),customer());var baseline=data(entries);
        var r=new Relation("x","ORDERS","APP","CUSTOMER",List.of("BUYER_NO"),List.of("CUSTOMER_ID"),"APPROVED","USER","고객 참조","",List.of(),null,null);
        var d=PropertyGraph.build("APP","business_graph",entries,new Analysis("APP",baseline.tables(),List.of(r),"now"));
        assertThat(d.vertices()).isEqualTo(2);assertThat(d.edges()).isEqualTo(1);
        assertThat(d.sql()).contains("CREATE PROPERTY GRAPH \"APP\".\"BUSINESS_GRAPH\"","\"ORDERS\" AS \"E1\" KEY (\"ORDER_ID\")","SOURCE KEY (\"ORDER_ID\") REFERENCES \"V1\"","DESTINATION KEY (\"BUYER_NO\") REFERENCES \"V2\" (\"CUSTOMER_ID\")","NO PROPERTIES","TRUSTED MODE").doesNotContain("OR REPLACE","ALL COLUMNS","GRANT","DROP");
    }
    @Test void businessGraphSelectionKeepsOnlyExplicitlyChosenConfirmedRelations(){
        var entries=List.of(orders(),customer());var baseline=data(entries);
        var relation=new Relation("choice","ORDERS","APP","CUSTOMER",List.of("BUYER_NO"),List.of("CUSTOMER_ID"),"APPROVED","USER","buyer","",List.of(),null,null);
        var analysis=new Analysis("APP",baseline.tables(),List.of(relation),"now");
        var all=PropertyGraph.build("APP","GRAPH",entries,analysis);
        assertThat(all.available()).hasSize(1);assertThat(all.edges()).isEqualTo(1);
        assertThat(PropertyGraph.build("APP","GRAPH",entries,analysis,List.of()).edges()).isZero();
        assertThatThrownBy(()->PropertyGraph.build("APP","GRAPH",entries,analysis,List.of("candidate"))).isInstanceOf(Failure.class);
    }
    @Test void graphExcludesUnconfirmedConditionalKeylessAndNonuniqueMappings(){
        var r=new Relation("x","ORDERS","APP","CUSTOMER",List.of("BUYER_NO"),List.of("CUSTOMER_ID"),"CANDIDATE","AI","r","",List.of(),null,null);
        var valid=List.of(orders(),customer());assertThat(PropertyGraph.build("APP","G",valid,new Analysis("APP",data(valid).tables(),List.of(r),"now")).edges()).isZero();
        var conditional=new Relation("x",r.source(),r.targetSchema(),r.target(),r.sourceColumns(),r.targetColumns(),"APPROVED","USER","r","active only",List.of(),null,null);
        assertThat(PropertyGraph.build("APP","G",valid,new Analysis("APP",data(valid).tables(),List.of(conditional),"now")).items()).anyMatch(i->i.reason().equals("CONDITION"));
        assertThat(PropertyGraph.build("APP","G",List.of(fixture.order(),customer()),data(List.of(fixture.order(),customer()))).items()).anyMatch(i->i.reason().equals("KEY_REQUIRED"));
    }
    @Test void namesAreQuotedAndGraphNameCannotContainSql(){
        assertThat(PropertyGraph.quote("a\"b")).isEqualTo("\"a\"\"b\"");
        for(String name:List.of("G; DROP TABLE X","APP.G","_G","G".repeat(61),"G\n"))assertThatThrownBy(()->PropertyGraph.graphName(name)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->PropertyGraph.graphName(null)).isInstanceOf(Failure.class);
    }
    @Test void graphPreviewIsOneUseAndVersionBound(){
        var entries=List.of(orders(),customer());var definition=PropertyGraph.build("APP","G",entries,data(entries));var now=Instant.now();var state=new OntologyPipeline.State();
        state.graph(new PropertyGraph.Draft("token","APP",definition,entries,now.plusSeconds(60)));
        assertThatThrownBy(()->state.consumeGraph("token","APP",false,now)).isInstanceOf(Failure.class);
        assertThat(state.consumeGraph("token","APP",true,now).definition()).isEqualTo(definition);
        assertThatThrownBy(()->state.consumeGraph("token","APP",true,now)).isInstanceOf(Failure.class);
        assertThat(PropertyGraph.sameDefinitions(entries,entries)).isTrue();assertThat(PropertyGraph.sameDefinitions(entries,List.of(orders()))).isFalse();
        assertThat(new PropertyGraph.Access(true,false,true,false,"").allowed()).isFalse();assertThat(new PropertyGraph.Access(true,true,true,true,"VALID").allowed()).isFalse();
    }
    @Test void reviewOnlyVersionsKeepApprovalsButChangedDefinitionsRequireReview(){
        var source=orders();var target=customer();var baseline=data(List.of(source,target));
        var request=new Review("APP","ORDERS",source.documentId(),1,"CUSTOMER",target.documentId(),1,List.of("BUYER_NO"),List.of("CUSTOMER_ID"),"","customer","","APPROVED");
        var link=OntologyRelations.review(request,source,target,baseline,"APP","now");var document=OntologyRelations.merge(source,link);
        var reviewed=new Entry("2",2,source.documentId(),"DRAFT","APP","now",document);
        var reviewOnly=new Entry("3",3,source.documentId(),"DRAFT","APP","now",document);
        assertThat(data(List.of(reviewed,target)).relations().getFirst().status()).isEqualTo("APPROVED");assertThat(data(List.of(reviewOnly,target)).relations().getFirst().status()).isEqualTo("APPROVED");
        var m=document.meaning();var changed=new Document(1,document.source(),new Meaning("changed concept",m.description(),m.columns(),m.relations()),document.origin(),null,null,document.links());
        assertThat(data(List.of(new Entry("4",4,source.documentId(),"DRAFT","APP","now",changed),target)).relations().getFirst().status()).isEqualTo("STALE");
        assertThat(OntologyRelations.definitionHash(Ontology.parse(json.writeValueAsString(document),json))).isEqualTo(OntologyRelations.definitionHash(document));
        var old=new Link(link.id(),link.targetDocumentId(),link.sourceRevision(),link.targetRevision(),link.targetSchema(),link.targetTable(),link.sourceColumns(),link.targetColumns(),link.label(),link.condition(),link.status(),link.origin(),List.of("USER_MAPPING"),link.actor(),link.reviewedAt());
        var oldDocument=OntologyRelations.merge(source,old);assertThat(data(List.of(new Entry("3",3,source.documentId(),"DRAFT","APP","now",oldDocument),target)).relations().getFirst().status()).isEqualTo("STALE");
    }
    @Test void remainingAiSuggestionsSurviveAnotherRelationshipReview(){
        var source=orders();var target=customer();var second=fixture.entry("SECOND",List.of(fixture.col("BUYER_NO","NUMBER")),List.of(),Map.of());var entries=List.of(source,target,second);var baseline=data(entries);var state=new OntologyPipeline.State();
        var batches=OntologyDiscovery.batches(entries,json);var plan=new OntologyDiscovery.Plan("token","APP",profile,batches,3,Instant.now().plusSeconds(60));state.prepare(plan,entries.stream().map(OntologyContext::reference).toList());
        state.authorize("token",0,true,"APP",Instant.now());state.begin("token",0,true,"APP",Instant.now());
        var a=OntologyDiscovery.parse(response("ORDERS","BUYER_NO"),batches.getFirst(),baseline,profile,json).getFirst();var b=OntologyDiscovery.parse(response("SECOND","BUYER_NO"),batches.getFirst(),baseline,profile,json).getFirst();state.finish(List.of(a,b),true);
        var req=new Review("APP","ORDERS",source.documentId(),1,"CUSTOMER",target.documentId(),1,a.sourceColumns(),a.targetColumns(),a.id(),a.label(),"","APPROVED");
        var link=OntologyRelations.review(req,source,target,state.augment(baseline),"APP","now");var reviewed=new Entry("2",2,source.documentId(),"DRAFT","APP","now",OntologyRelations.merge(source,link));state.reviewed(source,reviewed);
        assertThat(state.augment(data(List.of(reviewed,target,second))).relations()).anyMatch(r->r.source().equals("SECOND")&&r.origin().equals("AI"));
    }
    @Test void ddlRepositoryHasNoReplaceGrantDropOrBusinessRowScan() throws Exception {
        String code=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dbcompanion/repository/PropertyGraphRepository.java"));
        assertThat(code).contains("OWNER=? AND OBJECT_NAME=?","OWNER=? AND TABLE_NAME=?","R_CONSTRAINT_NAME","jdbc.execute(value.sql())").doesNotContain("OR REPLACE","GRANT ","DROP ","SELECT * FROM \"+");
    }
}
