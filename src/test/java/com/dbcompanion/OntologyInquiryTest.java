package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.service.*;
import com.dbcompanion.repository.OntologyQueryRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyInquiryTest {
    final JsonMapper json=new JsonMapper();final Instant now=Instant.parse("2026-09-20T12:00:00Z");
    Entry entry(String name,String concept,String state,List<Key> keys){
        var cols=List.of(new ColumnInfo(1,"CODE","NUMBER","N","code"),new ColumnInfo(2,"EMAIL","VARCHAR2(100)","Y","secret"));
        var def=new OntologyWizard.Definition("식별 코드",List.of("참조 번호"),"","","MATCH","reason","","APP.P","now",10);
        var source=new Snapshot("DB","APP",name,"original comment",cols,keys,"2026-09-20T10:00:00Z");
        var meaning=new Meaning(concept,"업무 정의 "+concept,Map.of("CODE",new ColumnMeaning("code description","UNKNOWN",def),"EMAIL",new ColumnMeaning("secret description","SENSITIVE")),Ontology.initial(source).relations());
        return new Entry("1",1,UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),state,"APP","now",new Document(1,source,meaning,"CATALOG",null));
    }
    Key fk(String status){return new Key("A_B_FK","R",List.of("CODE"),"APP","B",List.of("CODE"),status,"VALIDATED");}
    List<Entry> entries(){return List.of(entry("A","사용자","DRAFT",List.of(fk("ENABLED"))),entry("B","권역","APPROVED",List.of(new Key("B_PK","P",List.of("CODE"),null,null,List.of(),"ENABLED","VALIDATED"))));}
    OntologyInquiry.Dataset data(List<Entry> values){return new OntologyInquiry.Dataset("APP",values,OntologyRelations.analyze("DB","APP",values,now.toString()),now.toString());}
    OntologyInquiry.Search search(){return OntologyInquiry.search(data(entries()),"사용자는 어떤 권역에 속하는가?","");}
    AiAssistant.Profile profile(){return new AiAssistant.Profile(new AiAssistant.Selection("APP","LLM"),"oci","m","v1");}
    OntologyInquiry.Execution execution(OntologyInquiry.Search search){var sql="SELECT a.CODE FROM A a";return new OntologyInquiry.Execution(new OntologyInquiry.SqlDraft("exec",search.id(),sql,OntologyQueryService.hash(sql),true,"","APP.LLM",now.toString(),now.plusSeconds(600)),search,entries(),profile());}
    @Test void findsKoreanTermsAliasesAndDirectRelationsWithoutRowQueries(){
        var s=search();assertThat(s.matches()).extracting(OntologyInquiry.Match::table).contains("A","B");assertThat(s.references()).hasSize(2);
        assertThat(s.evidence().stream().filter(e->e.kind().equals("RELATION")).findFirst().orElseThrow().usable()).isTrue();
        assertThat(s.evidence().stream().filter(e->e.kind().equals("DEFINITION")&&e.source().equals("A")).findFirst().orElseThrow().usable()).isFalse();
        assertThat(OntologyInquiry.search(data(entries()),"참조 번호를 알려줘","").matches()).extracting(OntologyInquiry.Match::table).containsExactly("B");
        assertThat(OntologyInquiry.search(data(entries()),"unknown request","").evidence()).isEmpty();
        assertThat(OntologyInquiry.search(data(entries()),"unknown request","A").references()).hasSize(1);
        assertThat(OntologyInquiry.search(data(entries()),"EMAIL","").evidence()).isEmpty();
    }
    @Test void disabledForeignKeysAndUnapprovedDefinitionsCannotBeSelected(){
        var d=data(List.of(entry("A","사용자","DRAFT",List.of(fk("DISABLED"))),entries().get(1)));
        var s=OntologyInquiry.search(d,"사용자","");
        for(var e:s.evidence().stream().filter(e->!e.usable()).toList())assertThatThrownBy(()->OntologyInquiry.choose(s,List.of(e.id()))).isInstanceOf(Failure.class);
        assertThat(s.evidence().stream().filter(e->e.kind().equals("RELATION"))).allMatch(e->!e.usable());
        for(var ids:List.of(List.<String>of(),List.of("unknown"),List.of("M1","M1")))assertThatThrownBy(()->OntologyInquiry.choose(s,ids)).isInstanceOf(Failure.class);
    }
    @Test void onlySelectedEvidenceIsSentNoDraftMeaningsSensitiveColumnsOrUnrelatedKeys(){
        var s=search();var only=OntologyInquiry.choose(s,List.of("M1"));
        var selected=OntologyInquiry.selected(only,data(entries()));String payload=OntologyInquiry.payload(only,selected,json);
        assertThat(selected).hasSize(1);assertThat(payload).contains("M1","CODE").doesNotContain("EMAIL","secret description","original comment","업무 정의 사용자","A_B_FK");
        var ids=s.evidence().stream().filter(OntologyInquiry.Evidence::usable).map(OntologyInquiry.Evidence::id).toList();var full=OntologyInquiry.choose(s,ids);
        String all=OntologyInquiry.payload(full,OntologyInquiry.selected(full,data(entries())),json);
        assertThat(all).contains("업무 정의 권역","R1").doesNotContain("업무 정의 사용자","secret description","EMAIL");
        assertThat(ReviewedSql.scope(full,entries()).tables().get("A").columns()).containsExactly("CODE");
    }
    @Test void overflowInvalidUnicodeAndUnknownAnchorAreExplicitlyRejected(){
        var many=new ArrayList<Entry>();for(int i=0;i<11;i++)many.add(entry("T"+i,"공통용어","DRAFT",List.of()));
        var limited=OntologyInquiry.search(data(many),"공통용어","");assertThat(limited.limited()).isTrue();assertThat(limited.routes()).hasSize(3);
        for(String q:List.of("","x".repeat(2001),"a\u0000b","\uD800"))assertThatThrownBy(()->OntologyInquiry.search(data(entries()),q,"")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyInquiry.search(data(entries()),"question","NOT_HERE")).isInstanceOf(Failure.class);
    }
    @Test void answersRequireValidGroundingIdsAndStrictShortFormat(){
        var s=search();String valid="{\"status\":\"ANSWERED\",\"sentences\":[{\"text\":\"두 테이블이 FK로 연결됩니다.\",\"evidence\":[\"R1\"]}],\"limitation\":\"\"}";
        assertThat(OntologyInquiry.parse(valid,s,"APP.LLM",json).sentences()).hasSize(1);
        for(String bad:List.of(valid.replace("R1","unknown"),valid.replace("R1","D2"),valid.replace("ANSWERED","INSUFFICIENT"),valid.replace("두 테이블이 FK로 연결됩니다.","x".repeat(241)),valid.replace("[\"R1\"]","[]"),"SELECT * FROM A"))
            assertThatThrownBy(()->OntologyInquiry.parse(bad,s,"APP.LLM",json)).isInstanceOf(Failure.class);
        assertThat(OntologyInquiry.parse("{\"status\":\"INSUFFICIENT\",\"sentences\":[],\"limitation\":\"실제 행은 조회하지 않았습니다.\"}",s,"APP.LLM",json).status()).isEqualTo("INSUFFICIENT");
    }
    @Test void cacheRefreshAndSchemaChangeInvalidatePlans(){
        var state=new OntologyInquiry.State();var count=new AtomicInteger();java.util.function.Supplier<OntologyInquiry.Dataset> loader=()->{count.incrementAndGet();return data(entries());};
        state.dataset("APP",false,loader);state.dataset("APP",false,loader);assertThat(count).hasValue(1);
        state.remember(search());state.dataset("APP",true,loader);assertThat(count).hasValue(2);assertThatThrownBy(()->state.search(search().id(),"APP")).isInstanceOf(Failure.class);
        var session=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));session.inquiry().dataset("APP",false,loader);session.selectSchema("OTHER");assertThatThrownBy(()->session.inquiry().data()).isInstanceOf(Failure.class);
    }
    @Test void invalidationDuringGenerationCannotResurrectSqlAndExecutionIsOneUse(){
        var state=new OntologyInquiry.State();var s=search();state.remember(s);var p=new OntologyInquiry.Prepared("ai","SQL",s,entries());state.prepare(p);
        assertThat(state.consume("ai","APP")).isEqualTo(p);state.current("ai");state.invalidate();assertThatThrownBy(()->state.current("ai")).isInstanceOf(Failure.class);
        state.prepare(p);state.cancel("ai");assertThatThrownBy(()->state.current("ai")).isInstanceOf(Failure.class);
        state.execution(execution(s));assertThatThrownBy(()->state.execute("exec","APP",false,now)).isInstanceOf(Failure.class);
        assertThat(state.execute("exec","APP",true,now).draft().hash()).hasSize(64);assertThatThrownBy(()->state.execute("exec","APP",true,now)).isInstanceOf(Failure.class);
        state.execution(execution(s));assertThatThrownBy(()->state.execute("exec","APP",true,now.plusSeconds(600))).isInstanceOf(Failure.class);
        state.execution(execution(s));assertThatThrownBy(()->state.execute("exec","OTHER",true,now)).isInstanceOf(Failure.class);assertThatThrownBy(()->state.execute("exec","APP",true,now)).isInstanceOf(Failure.class);
    }
    @Test void profileScopeAndCallBoundariesAreNarrowAndNonPersistent() throws Exception {
        assertThat(OntologyQueryRepository.covers("[{\"owner\":\"APP\",\"name\":\"A\"}]","APP",List.of("A"),json)).isTrue();
        assertThat(OntologyQueryRepository.covers("[{\"owner\":\"APP\"}]","APP",List.of("A","B"),json)).isTrue();
        for(String input:List.of("[]","broken","[{\"owner\":\"OTHER\"}]","[{\"owner\":\"APP\",\"name\":\"B\"}]"))assertThat(OntologyQueryRepository.covers(input,"APP",List.of("A"),json)).isFalse();
        assertThat(OntologyQueryRepository.generationSql("C##CLOUD$SERVICE")).contains("action => 'showsql'","prompt => ?","attributes => ?","GET_CONVERSATION_ID IS NOT NULL").doesNotContain("runsql","SET_PROFILE","SET_ATTRIBUTE");
        var preview=new AiAssistant.Preview("ai","Q",profile(),"SELECT AI runsql delete from A",30,false,now.plusSeconds(600));var draft=new AiAssistant.Draft(preview,"APP","ko","inquiry");
        assertThat(OntologyInquiry.sqlPrompt(draft)).startsWith("Generate only one Oracle SELECT").contains("No SELECT *");
        assertThat(OntologyInquiry.prompt(draft)).contains("ONLY the supplied evidence","NOT a business-row query","INSUFFICIENT",
            "name intermediate tables", "Path order is traversal order, not FK direction", "Missing business rows alone does not prevent",
            "never invent them", "those values are not answered", "not business authorization", "Do not equate multi-hop reachability");
        String service=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dbcompanion/service/OntologyQueryService.java"));
        assertThat(service).contains("strict.setEnforceReadOnly(true)","execute.setReadOnly(true)","graphs.verifyMetadata", "state.current(token)","\"conversation\",false");
        String repository=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dbcompanion/repository/OntologyQueryRepository.java"));
        assertThat(repository).contains("execute(value.search().id(),value.draft().sql(),value.draft().hash(),actor)","c.prepareStatement(sql)","stmt.setQueryTimeout(15)","stmt.setMaxRows(201)","VIRTUAL_COLUMN='YES'","SYS.ALL_EXTERNAL_TABLES").doesNotContain("OFFSET", "runsql");
    }
}
