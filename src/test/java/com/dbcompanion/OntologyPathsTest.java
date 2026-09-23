package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyPathsTest {
    ColumnInfo c(String name,String comment){return new ColumnInfo(1,name,"NUMBER","N",comment);}
    Key pk(String col){return new Key("PK_"+col,"P",List.of(col),null,null,List.of(),"ENABLED","VALIDATED");}
    Key fk(String name,String from,String to,String target){return new Key(name,"R",List.of(from),"APP",to,List.of(target),"ENABLED","VALIDATED");}
    Entry entry(String name,String concept,String comment,List<ColumnInfo> cols,List<Key> keys){
        var s=new Snapshot("DB","APP",name,comment,cols,keys,"now");var m=Ontology.initial(s);
        return new Entry("1",1,UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"DRAFT","APP","now",new Document(1,s,new Meaning(concept,comment,m.columns(),m.relations()),"CATALOG",null));
    }
    List<Entry> sample(){return List.of(
        entry("DX_APP_USER","","데모 애플리케이션 사용자 기준정보. 한 행은 하나의 업무 사용자 계정을 나타내며 보안 관리 대상이다.",List.of(c("APP_USER_ID","애플리케이션 사용자를 식별하는 기본 키.")),List.of(pk("APP_USER_ID"))),
        entry("DX_REGION","","권역 기준정보",List.of(c("REGION_ID","권역 식별 기본 키")),List.of(pk("REGION_ID"))),
        entry("DX_USER_ENTITLEMENT","사용자별 권한 부여 기준정보","사용자와 권역별 권한",List.of(c("APP_USER_ID","사용자"),c("REGION_ID","권역")),List.of(fk("FK_USER","APP_USER_ID","DX_APP_USER","APP_USER_ID"),fk("FK_REGION","REGION_ID","DX_REGION","REGION_ID"))),
        entry("DX_PART_COST","","권역별 부품 원가",List.of(c("REGION_ID","권역"),c("PART_ID","부품")),List.of(fk("FK_COST_REGION","REGION_ID","DX_REGION","REGION_ID"),fk("FK_PART","PART_ID","DX_PART","PART_ID"))),
        entry("DX_PART","","부품 기준정보",List.of(c("PART_ID","부품 식별 키")),List.of(pk("PART_ID"))));}
    OntologyInquiry.Dataset data(List<Entry> entries){return new OntologyInquiry.Dataset("APP",entries,OntologyRelations.analyze("DB","APP",entries,"now"),"now");}
    @Test void bridgeSurrogateKeyDoesNotReplaceTheReferencedEntities(){
        var entries=new ArrayList<>(sample());var old=entries.get(2).document().source();
        var columns=new ArrayList<>(old.columns());columns.add(c("ENTITLEMENT_ID","사용자별 권한 정보를 식별하는 키"));
        var keys=new ArrayList<>(old.keys());keys.add(pk("ENTITLEMENT_ID"));
        entries.set(2,entry(old.table(),"사용자별 권한 부여 기준정보",old.comment(),columns,keys));
        var search=OntologyInquiry.search(data(entries),"사용자의 권역은 어떻게 되나요?","");
        assertThat(search.routes().getFirst().tables()).containsExactly("DX_APP_USER","DX_USER_ENTITLEMENT","DX_REGION");
        assertThat(search.routes().getFirst().relations()).hasSize(2);
    }
    List<Entry> genericBridge(String status,String validated){return List.of(
        entry("CAT_A","","Alpha reference catalog",List.of(c("ID","")),List.of(pk("ID"))),
        entry("CAT_B","","Beta reference catalog",List.of(c("ID","")),List.of(pk("ID"))),
        entry("LINKS","","Alpha Beta assignments",List.of(c("LINK_ID","Alpha"),c("REF_A","Alpha"),c("REF_B","Beta")),List.of(pk("LINK_ID"),
            new Key("A_FK","R",List.of("REF_A"),"APP","CAT_A",List.of("ID"),status,validated),
            new Key("B_FK","R",List.of("REF_B"),"APP","CAT_B",List.of("ID"),status,validated))));}
    @Test void genericBridgeWithBothTermsAndWeakParentCommentsStillFindsBothEndpoints(){
        var entries=genericBridge("ENABLED","VALIDATED");var search=OntologyInquiry.search(data(entries),"Alpha Beta","");
        assertThat(search.routes()).hasSize(1);assertThat(search.routes().getFirst().tables()).containsExactly("CAT_A","LINKS","CAT_B");
        assertThat(search.routes().getFirst().relations()).hasSize(2);
        assertThat(OntologyInquiry.search(data(entries),"LINKS","").routes().getFirst().tables()).containsExactly("LINKS");
        var reversed=new ArrayList<>(entries);Collections.reverse(reversed);
        assertThat(OntologyInquiry.search(data(reversed),"Alpha Beta","").routes()).isEqualTo(search.routes());
    }
    @Test void actualRegionTerminologyInTableCommentDoesNotRequireSameWordOnForeignKey(){
        var entries=new ArrayList<>(sample());var bridge=entries.get(2).document().source();
        entries.set(1,entry("DX_REGION","","글로벌 판매 및 서비스 권역 기준정보. 한 행은 하나의 글로벌 거점을 나타낸다.",
            List.of(c("REGION_ID","글로벌 거점을 식별하는 기본 키.")),List.of(pk("REGION_ID"))));
        var columns=List.of(c("ENTITLEMENT_ID","사용자 권한 행을 식별하는 기본 키."),
            c("APP_USER_ID","권한을 부여받는 애플리케이션 사용자를 가리키는 외래 키."),
            c("REGION_ID","조회 가능한 글로벌 거점을 가리키는 외래 키. 전역 권한이면 값이 없을 수 있다."));
        var keys=new ArrayList<>(bridge.keys());keys.add(pk("ENTITLEMENT_ID"));
        entries.set(2,entry(bridge.table(),"사용자별 권한 부여 기준정보","사용자와 권역의 조회 권한 조합을 저장하는 보안 관리 테이블",columns,keys));
        var result=OntologyInquiry.search(data(entries),"사용자의 권역은 어떻게 되나요?","");
        assertThat(result.routes()).hasSize(1);
        assertThat(result.routes().getFirst().tables()).containsExactly("DX_APP_USER","DX_USER_ENTITLEMENT","DX_REGION");
        assertThat(result.routes().getFirst().relations()).hasSize(2);
    }
    @Test void differingReferenceTerminologyDoesNotDemoteAnIndependentKeyDefinition(){
        var parent=entry("MASTER","","Alpha reference catalog",List.of(c("ID","")),List.of(pk("ID")));
        var child=entry("DETAIL","","",List.of(c("ID","Alpha"),c("REF_ID","different concept")),
            List.of(pk("ID"),fk("REF","REF_ID","MASTER","ID")));
        assertThat(OntologyInquiry.search(data(List.of(parent,child)),"Alpha","").routes().getFirst().tables()).containsExactly("DETAIL");
    }
    @Test void invalidRelationsDoNotDemoteLocalMeaningOrInventConnectedEvidence(){
        for(var flags:List.of(List.of("DISABLED","VALIDATED"),List.of("ENABLED","NOT VALIDATED"))){
            var search=OntologyInquiry.search(data(genericBridge(flags.get(0),flags.get(1))),"Alpha Beta","");
            assertThat(search.routes().getFirst().tables()).containsExactly("LINKS");
            assertThat(search.routes().getFirst().relations()).isEmpty();
        }
    }
    @Test void sensitiveMappingCannotShiftTheEntityRanking(){
        var entries=new ArrayList<>(genericBridge("ENABLED","VALIDATED"));var bridge=entries.get(2);var doc=bridge.document();var m=doc.meaning();
        var columns=new LinkedHashMap<>(m.columns());columns.put("REF_A",new ColumnMeaning("Alpha","SENSITIVE"));
        entries.set(2,new Entry(bridge.seq(),bridge.revision(),bridge.documentId(),bridge.state(),bridge.actor(),bridge.recordedAt(),
            new Document(1,doc.source(),new Meaning(m.concept(),m.description(),columns,m.relations()),doc.origin(),null)));
        var search=OntologyInquiry.search(data(entries),"Alpha Beta","");
        assertThat(search.concepts().getFirst().targets()).extracting(OntologyInquiry.Target::table).containsExactly("LINKS");
        assertThat(search.routes().getFirst().tables()).containsExactly("LINKS","CAT_B");
        assertThat(search.evidence().stream().filter(e->e.kind().equals("RELATION"))).noneMatch(e->e.from().contains("REF_A"));
    }
    @Test void exactEntityMeaningWinsOverOutgoingReference(){
        var entries=new ArrayList<>(genericBridge("ENABLED","VALIDATED"));var bridge=entries.get(2).document().source();
        entries.set(2,entry(bridge.table(),"Alpha",bridge.comment(),bridge.columns(),bridge.keys()));
        assertThat(OntologyInquiry.search(data(entries),"Alpha","").routes().getFirst().tables()).containsExactly("LINKS");
    }
    @Test void selectedPathExplicitlyNamesIntermediateTableAndOnlyItsRelationships(){
        var entries=genericBridge("ENABLED","VALIDATED");var search=OntologyInquiry.search(data(entries),"Alpha Beta","");
        var chosen=OntologyInquiry.chooseRoute(search,search.routes().getFirst().id());var json=new JsonMapper();
        var payload=json.readTree(OntologyInquiry.payload(chosen,OntologyInquiry.selected(chosen,data(entries)),json));
        assertThat(payload.path("paths").size()).isEqualTo(1);
        assertThat(payload.path("paths").get(0).path("tables").toString()).isEqualTo("[\"CAT_A\",\"LINKS\",\"CAT_B\"]");
        assertThat(payload.path("paths").get(0).path("relations").size()).isEqualTo(2);
        assertThat(payload.toString()).doesNotContain("Alpha Beta assignments");
        var partial=OntologyInquiry.choose(search,List.of(search.evidence().getFirst().id()));
        assertThat(json.readTree(OntologyInquiry.payload(partial,OntologyInquiry.selected(partial,data(entries)),json)).path("paths").isEmpty()).isTrue();
    }
    @Test void choosingOneRouteDoesNotRetainOtherCandidatesContainedInItsEvidence(){
        var entries=genericBridge("ENABLED","VALIDATED");var result=OntologyInquiry.search(data(entries),"Alpha Beta","");
        var full=result.routes().getFirst();var names=full.tables().subList(0,2);var rels=full.relations().subList(0,1);
        var ids=result.evidence().stream().filter(e->e.usable()&&(e.kind().equals("RELATION")?rels.contains(e.id()):names.contains(e.source()))).map(OntologyInquiry.Evidence::id).toList();
        var shortRoute=new OntologyInquiry.Route("SHORT",names,rels,ids,0);
        var search=new OntologyInquiry.Search(result.id(),result.schema(),result.question(),result.anchor(),result.matches(),result.evidence(),result.references(),result.checkedAt(),result.concepts(),List.of(shortRoute,full),result.tables(),result.limited());
        var selected=OntologyInquiry.chooseRoute(search,full.id());
        assertThat(selected.routes()).containsExactly(full);
        assertThat(new JsonMapper().readTree(OntologyInquiry.payload(selected,OntologyInquiry.selected(selected,data(entries)),new JsonMapper())).path("paths").size()).isEqualTo(1);
    }
    @Test void userRegionQuestionFindsBridgeWithoutCostOrPartBranches(){
        var entries=sample();var s=OntologyInquiry.search(data(entries),"사용자의 권역은 어떻게 되나요?","");
        assertThat(s.concepts()).extracting(OntologyInquiry.Concept::term).containsExactly("사용자","권역");assertThat(s.routes()).hasSize(1);
        var route=s.routes().getFirst();assertThat(route.tables()).containsExactly("DX_APP_USER","DX_USER_ENTITLEMENT","DX_REGION");assertThat(route.relations()).hasSize(2).doesNotContainNull();
        assertThat(s.references()).hasSize(3);assertThat(s.tables()).extracting(OntologyInquiry.TableContext::name).doesNotContain("DX_PART_COST","DX_PART");
        var chosen=OntologyInquiry.chooseRoute(s,route.id());assertThat(chosen.evidence()).hasSize(5);assertThat(chosen.evidence()).allMatch(OntologyInquiry.Evidence::usable);
        assertThat(chosen.evidence()).noneMatch(e->e.kind().equals("DEFINITION"));String payload=OntologyInquiry.payload(chosen,OntologyInquiry.selected(chosen,data(entries)),new JsonMapper());
        assertThat(payload).contains("DX_APP_USER","DX_USER_ENTITLEMENT","DX_REGION").doesNotContain("DX_PART_COST","사용자별 권한 부여 기준정보");
    }
    @Test void explicitlyChoosingTheMiddleTableStillFindsBothQuestionEndpoints(){
        var s=OntologyInquiry.search(data(sample()),"사용자의 권역은 어떻게 되나요?","DX_USER_ENTITLEMENT");
        // A path may start at any required endpoint; the anchor is an included node, not an imposed direction.
        assertThat(s.routes()).isNotEmpty();assertThat(s.routes().getFirst().tables()).contains("DX_APP_USER","DX_USER_ENTITLEMENT","DX_REGION");
    }
    @Test void oneConceptAndExplicitTableDoNotAutomaticallyExpandNeighbors(){
        var s=OntologyInquiry.search(data(sample()),"사용자 설명","");assertThat(s.routes().getFirst().tables()).containsExactly("DX_APP_USER");assertThat(s.evidence()).hasSize(2);
        var exact=OntologyInquiry.search(data(sample()),"dx_app_user 설명","");assertThat(exact.concepts()).hasSize(1);assertThat(exact.routes().getFirst().tables()).containsExactly("DX_APP_USER");
        assertThat(OntologyInquiry.search(data(sample()),"unmatched","DX_PART_COST").routes().getFirst().tables()).containsExactly("DX_PART_COST");
    }
    @Test void parallelForeignKeysRemainDifferentChoicesAndDirectionsAreRetained(){
        var region=sample().get(1);var shipping=entry("SHIPMENT","운송","운송",List.of(c("ID","운송 키"),c("FROM_ID","출발 권역"),c("TO_ID","도착 권역")),List.of(pk("ID"),fk("FK_FROM","FROM_ID","DX_REGION","REGION_ID"),fk("FK_TO","TO_ID","DX_REGION","REGION_ID")));
        var s=OntologyInquiry.search(data(List.of(shipping,region)),"운송의 권역","");assertThat(s.routes()).hasSize(2);
        assertThat(s.routes().stream().map(r->r.relations().getFirst())).doesNotHaveDuplicates();
        assertThat(s.evidence().stream().filter(e->e.kind().equals("RELATION")).map(OntologyInquiry.Evidence::from)).containsExactlyInAnyOrder(List.of("FROM_ID"),List.of("TO_ID"));
    }
    @Test void noConfirmedConnectionDoesNotInventARoute(){
        var a=entry("A","Alpha","",List.of(c("CODE","")),List.of());var b=entry("B","Beta","",List.of(c("CODE","")),List.of(pk("CODE")));
        var d=data(List.of(a,b));assertThat(OntologyInquiry.search(d,"Alpha Beta","").routes()).isEmpty();
        for(String status:List.of("DISABLED","ENABLED")){
            var invalid=new Key("FK","R",List.of("CODE"),"APP","B",List.of("CODE"),status,"NOT VALIDATED");
            assertThat(OntologyInquiry.search(data(List.of(entry("A","Alpha","",a.document().source().columns(),List.of(invalid)),b)),"Alpha Beta","").routes()).isEmpty();
        }
    }
    @Test void selectedRouteCannotBeForgedOrBorrowedFromAnotherSearch(){
        var s=OntologyInquiry.search(data(sample()),"사용자 권역","");
        assertThatThrownBy(()->OntologyInquiry.chooseRoute(s,"P99")).isInstanceOf(Failure.class);assertThatThrownBy(()->OntologyInquiry.chooseRoute(s,null)).isInstanceOf(Failure.class);
        var state=new OntologyInquiry.State();state.remember(s);var next=OntologyInquiry.search(data(sample()),"부품","");state.remember(next);
        assertThatThrownBy(()->state.search(s.id(),"APP")).isInstanceOf(Failure.class);assertThat(OntologyInquiry.chooseRoute(next,"P1").references()).hasSize(1);
    }
    @Test void loopsAndLongPathsAreBoundedAndInputOrderDoesNotChangeTopResult(){
        var values=new ArrayList<>(sample());var first=OntologyInquiry.search(data(values),"사용자 권역","").routes().getFirst().tables();Collections.reverse(values);
        assertThat(OntologyInquiry.search(data(values),"사용자 권역","").routes().getFirst().tables()).isEqualTo(first);
        var chain=new ArrayList<Entry>();for(int i=0;i<5;i++)chain.add(entry("T"+i,i==0?"Start":i==4?"Finish":"", "",List.of(c("ID",""),c("NEXT_ID","")),i==4?List.of(pk("ID")):List.of(pk("ID"),fk("FK"+i,"NEXT_ID","T"+(i+1),"ID"))));
        assertThat(OntologyInquiry.search(data(chain),"Start Finish","").routes()).isEmpty();
    }
}
