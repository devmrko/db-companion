package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class IndependentEvidenceTest {
    final OntologyInquiryTest fixtures=new OntologyInquiryTest();
    final JsonMapper json=new JsonMapper();
    final String question="일반 지표 / 사업 지표 2028-02-03 알려줘";
    List<Ontology.Entry> entries(){return List.of(fixtures.entry("STANDARD_METRIC","일반 지표","APPROVED",List.of()),fixtures.entry("BUSINESS_METRIC","사업 지표","APPROVED",List.of()),fixtures.entry("DRAFT_METRIC","초안","DRAFT",List.of()));}
    SelectAiEvidence.State state(){var state=new SelectAiEvidence.State();state.dataset("APP",false,()->fixtures.data(entries()));return state;}
    @Test void disconnectedApprovedDefinitionsCanBeExplicitlyCombinedWithoutInventingAPath(){
        var state=state();assertThat(state.search("APP",question,"").routes()).isEmpty();
        var value=state.definitions("APP",question,List.of("STANDARD_METRIC","BUSINESS_METRIC"),json);
        assertThat(value.route()).isEqualTo("DEFINITIONS");assertThat(value.question()).isEqualTo(question);
        assertThat(value.references()).extracting(OntologyAnalysis.Reference::table).containsExactly("STANDARD_METRIC","BUSINESS_METRIC");
        var payload=json.readTree(value.source());assertThat(payload.path("paths").isEmpty()).isTrue();
        assertThat(payload.path("mode").asString()).isEqualTo("APPROVED_DEFINITIONS_ONLY");
        assertThat(payload.has("rdf")).isFalse();assertThat(payload.path("tableMetadata").size()).isEqualTo(2);
        assertThat(value.source()).contains("original comment","code description","식별 코드","NUMBER");
        assertThat(payload.path("evidence").size()).isEqualTo(2);
        for(var item:payload.path("evidence"))assertThat(item.path("kind").asString()).isEqualTo("DEFINITION");
        assertThat(value.source()).doesNotContain("EMAIL","secret","DRAFT_METRIC");
        assertThat(state.resolve(true,value.hash(),question)).isSameAs(value);
        SelectAiEvidence.verify(value,value.entries());
        assertThatThrownBy(()->state.resolve(true,value.hash(),"changed")).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void definitionSelectionFailsClosedOnScopeApprovalDuplicatesAndLimits(){
        var state=state();
        assertThatThrownBy(()->state.definitions("APP",question,List.of(),json)).isInstanceOf(AiAssistant.Failure.class);
        for(var names:List.of(List.of("MISSING"),List.of("DRAFT_METRIC"),List.of("STANDARD_METRIC","STANDARD_METRIC"),Collections.nCopies(11,"STANDARD_METRIC"),Arrays.asList((String)null)))
            assertThatThrownBy(()->state.definitions("APP",question,names,json)).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->state.definitions("APP",question,null,json)).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->state.definitions("OTHER",question,List.of("STANDARD_METRIC"),json)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->state.definitions("APP","\uD800",List.of("STANDARD_METRIC"),json)).isInstanceOf(AiAssistant.Failure.class);
        var value=state.definitions("APP",question,List.of("STANDARD_METRIC"),json);state.invalidate();
        assertThatThrownBy(()->state.resolve(true,value.hash(),question)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void compactDefinitionPayloadPreservesSemanticsAndLeavesRoomForDictionary(){
        var values=entries();var chosen=OntologyInquiry.definitions(fixtures.data(values),question,List.of("STANDARD_METRIC","BUSINESS_METRIC"));
        var selected=OntologyInquiry.selected(chosen,fixtures.data(values));
        String rdf=OntologyInquiry.payload(chosen,selected,json),compact=OntologyInquiry.definitionPayload(chosen,selected,json);
        assertThat(compact.length()).isLessThan(rdf.length());
        var old=json.readTree(rdf);var current=json.readTree(compact);
        for(String key:List.of("evidence","paths","approvedColumnDefinitions","approvedValueMappings","question"))assertThat(current.path(key)).isEqualTo(old.path(key));
        var now=java.time.Instant.parse("2028-01-01T00:00:00Z");
        var term=new BusinessGlossary.Term("00000000-0000-0000-0000-000000000001",1,"일반 지표",List.of(),"일반 지표의 승인 기준","COUNT(DISTINCT USER_ID)",true,"v1");
        var search=new BusinessGlossary.Search("s","APP","P",question,"EXACT_ALIAS",List.of(),List.of(),"",List.of(new BusinessGlossary.Hit(term,"TERM",List.of())),false,now.plusSeconds(60));
        var dictionary=BusinessGlossary.snapshot(search,search.hits(),json,now);
        String prompt=BusinessGlossary.append(SelectAiTest.prompt(SelectAiTest.Action.SQL,question,"ko",state().definitions("APP",question,List.of("STANDARD_METRIC","BUSINESS_METRIC"),json)),dictionary);
        assertThat(prompt).contains(question,"BUSINESS DICTIONARY EVIDENCE","original comment","일반 지표의 승인 기준");
        assertThat(prompt.length()).isLessThan(AiAssistant.MAX_SOURCE);
    }
    @Test void calendarLiteralsAreNotMandatoryConceptsButOriginalQuestionIsPreserved(){
        var paths=new OntologyPathsTest();
        var dataset=paths.data(List.of(paths.entry("METRIC","일반 지표","",List.of(paths.c("ID","")),List.of()),paths.entry("NOISE","재화","2028 source gap",List.of(paths.c("ID","")),List.of())));
        for(String date:List.of("2028-02-03","2028/02/03","2028.02.03","2028년 2월 3일")){
            String input="일반 지표 "+date+" 알려줘";
            var analysis=new BusinessGlossary.Analysis("ORACLE_TEXT",List.of(new BusinessGlossary.Token("일반",1,2),new BusinessGlossary.Token("지표",4,2)));
            var search=OntologyInquiry.search(dataset,input,"",analysis);
            assertThat(search.question()).isEqualTo(input);assertThat(search.concepts()).extracting(OntologyInquiry.Concept::term).containsExactly("일반 지표");
            assertThat(search.routes().getFirst().tables()).containsExactly("METRIC");
        }
        var numeric=paths.data(List.of(paths.entry("CODE_TABLE","10001","",List.of(paths.c("ID","")),List.of())));
        assertThat(OntologyInquiry.search(numeric,"10001 알려줘","").routes()).hasSize(1);
    }
}
