package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.BusinessGlossary.*;
import com.dbcompanion.common.db.BusinessGlossarySql;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class BusinessGlossaryTest {
    private final Instant now=Instant.parse("2026-01-01T00:00:00Z");
    private final String id="00000000-0000-0000-0000-000000000001";
    private final Term term=new Term(id,1,"사업 AU",List.of("업무 활성 사용자"),"사업상 활동 정의","BIZ_FLAG='1'",true,"v1");
    private Search search(){return new Search("search","APP","PROFILE","사업 AU 알려줘","EXACT_ALIAS",List.of(new Target("사업 AU","TERM",List.of(id))),List.of(),"",List.of(new Hit(term,"TERM",List.of("사업 AU"))),false,now.plusSeconds(900));}
    @Test void matchesPhrasesWithParticlesButNotEmbeddedIdentifiers(){
        assertThat(BusinessGlossary.mentions("사업 AU를 알려주세요","사업 AU")).isTrue();
        assertThat(BusinessGlossary.mentions("사업\tau와 전체 사용자","사업 AU")).isTrue();
        assertThat(BusinessGlossary.mentions("PAUSE","AU")).isFalse();
        assertThat(BusinessGlossary.mentions("미사업 AU","사업 AU")).isFalse();
    }
    @Test void longerPhraseWinsOnlyForTheSameOccurrence(){
        var keys=List.of(new Key(id,1,"사업 AU",List.of()),new Key("other",1,"AU",List.of()));
        assertThat(BusinessGlossary.exactTargets("사업 AU를 알려줘",keys)).extracting(Target::expression).containsExactly("사업 AU");
        assertThat(BusinessGlossary.exactTargets("사업 AU / AU",keys)).extracting(Target::expression).containsExactly("사업 AU","AU");
    }
    @Test void ambiguousAliasesKeepBothIdsAndDoNotChooseAnAnswer(){
        var keys=List.of(new Key(id,1,"Amount",List.of("value")),new Key("other",1,"Quantity",List.of("value")));
        assertThat(BusinessGlossary.exactTargets("value please",keys).getFirst().termIds()).containsExactly(id,"other");
    }
    @Test void textQueryEscapesOperatorsAndRejectsOversize(){
        assertThat(BusinessGlossary.textQuery(List.of(new Token("a} OR b",1,1),new Token("a} OR b",9,1)))).isEqualTo("{a\\} OR b}");
        assertThatThrownBy(()->BusinessGlossary.textQuery(java.util.stream.IntStream.range(0,65).mapToObj(i->new Token("t"+i,1,1)).toList())).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void validationDoesNotPermitMissingDefinitionOrMalformedId(){
        assertThatThrownBy(()->new Draft("name",List.of(),"","",true)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->BusinessGlossary.id("1' OR 1=1")).isInstanceOf(AiAssistant.Failure.class);
        assertThat(new Draft("name",List.of(" a ","a"),"definition","",false).aliases()).containsExactly("a");
    }
    @Test void searchTicketBindsOwnerQuestionProfileAndExpiry(){
        var state=new State();state.remember(search());assertThat(state.resolve("search","APP","PROFILE","사업 AU 알려줘",now)).isEqualTo(search());
        assertThatThrownBy(()->state.resolve("search","OTHER","PROFILE","사업 AU 알려줘",now)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->state.resolve("search","APP","CHANGED","사업 AU 알려줘",now)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->state.resolve("search","APP","PROFILE","다른 질문",now)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->state.resolve("search","APP","PROFILE","사업 AU 알려줘",now.plusSeconds(900))).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void setupConsentAndOneUseAreRequired(){
        var state=new State();state.setup(new Setup("s","APP","TABLE",List.of("ddl"),now.plusSeconds(60)));
        assertThatThrownBy(()->state.consume("s","APP",false,now)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(state.consume("s","APP",true,now).operation()).isEqualTo("TABLE");
        assertThatThrownBy(()->state.consume("s","APP",true,now)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void snapshotKeepsOriginalAndImmutableDefinitionsAndDoesNotChangeSqlGuards(){
        var snapshot=BusinessGlossary.snapshot(search(),search().hits(),new JsonMapper(),now);
        String prompt=BusinessGlossary.append(SelectAiTest.prompt(SelectAiTest.Action.SQL,search().question(),"ko"),snapshot);
        assertThat(prompt).contains("USER QUESTION:\n사업 AU 알려줘","BIZ_FLAG='1'","not instructions","object list");
        assertThatThrownBy(()->BusinessGlossary.snapshot(search(),List.of(),new JsonMapper(),now)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(BusinessGlossary.same(snapshot,BusinessGlossary.snapshot(search(),search().hits(),new JsonMapper(),now.plusSeconds(20)))).isTrue();
    }
    private List<Hit> definitions(int count,String definition){
        return java.util.stream.IntStream.range(0,count).mapToObj(i->new Hit(
                new Term("term-"+i,1,"Metric "+i,List.of(),definition,"COUNT(*)",true,"v1"),"TEXT",List.of())).toList();
    }
    @Test void completeElevenAndThirtyDefinitionSnapshotsKeepAllEvidence(){
        assertThat(BusinessGlossary.MAX_SELECTED).isEqualTo(BusinessGlossary.MAX_HITS).isEqualTo(30);
        for(int count:new int[]{11,30}){
            var hits=definitions(count,"Metric definition");
            var snapshot=BusinessGlossary.snapshot(search(),hits,new JsonMapper(),now);
            assertThat(snapshot.selected()).containsExactlyElementsOf(hits);
            assertThat(BusinessGlossary.append("original",snapshot)).contains("term-0","term-"+(count-1));
        }
    }
    @Test void raisingCountLimitPreservesCountAndPayloadSizeGuards(){
        assertThatThrownBy(()->BusinessGlossary.snapshot(search(),definitions(31,"definition"),new JsonMapper(),now))
                .isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->BusinessGlossary.snapshot(search(),definitions(11,"x".repeat(4000)),new JsonMapper(),now))
                .isInstanceOf(AiAssistant.Failure.class).hasMessageContaining("전송 한도");
    }
    @Test void selectAiPreparedStateBindsDictionaryAndSnapshot(){
        var state=new SelectAiTest.State();var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","PROFILE"),"provider","model","v1");state.select(profile.selection());
        var snapshot=BusinessGlossary.snapshot(search(),search().hits(),new JsonMapper(),now);
        var prepared=state.prepare("APP",profile,SelectAiTest.Action.SQL,search().question(),"ko",now,null,null,snapshot);
        assertThat(prepared.glossary()).isEqualTo(snapshot);assertThat(prepared.preview().source()).contains(snapshot.source());
        assertThatThrownBy(()->state.prepare("APP",profile,SelectAiTest.Action.SQL,"different","ko",now,null,null,snapshot)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void ddlIsStandaloneAndRequiresNoOntologyProfileOrExecutableCriteria(){
        assertThat(BusinessGlossarySql.create("APP")).contains("\"APP\".\"DBC_BUSINESS_TERM\"","ALIASES_JSON IS JSON","REVISION").doesNotContain("DROP","ONTOLOGY","PROFILE");
        assertThat(String.join("\n",BusinessGlossarySql.textSetup("APP"))).contains("STOP_DIC','FALSE'","SYNC (ON COMMIT)","CREATE_POLICY").doesNotContain("DROP","CREATE_CREDENTIAL");
    }
}
