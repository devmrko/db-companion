package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.BusinessGlossary.*;
import com.dbcompanion.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class NativeQuestionTokensTest {
    final OntologyInquiryTest fixtures=new OntologyInquiryTest();
    Analysis tokens(String... words){return new Analysis("ORACLE_TEXT",Arrays.stream(words).map(w->new Token(w,1,w.length())).toList());}
    Ontology.Entry labelled(String table,String concept,String label){
        var entry=fixtures.entry(table,concept,"APPROVED",List.of());var doc=entry.document();var m=doc.meaning();
        var columns=new LinkedHashMap<>(m.columns());
        columns.put("CODE",new Ontology.ColumnMeaning("approved field","UNKNOWN",new OntologyWizard.Definition(label,List.of(),"","","MATCH","reviewed","","APP.P","now",1)));
        return new Ontology.Entry(entry.seq(),entry.revision(),entry.documentId(),entry.state(),entry.actor(),entry.recordedAt(),new Ontology.Document(1,doc.source(),new Ontology.Meaning(m.concept(),m.description(),columns,m.relations()),doc.origin(),null));
    }
    @Test void savedContextNarrowsSpecificLabelsBeforeCandidateTruncation(){
        var entries=new ArrayList<Ontology.Entry>();
        for(int i=0;i<6;i++)entries.add(labelled("ORBIT_"+i,"Orbit data "+i,"Metric"+i));
        entries.add(labelled("OTHER_METRIC","Different application","Metric0"));
        var result=OntologyInquiry.search(fixtures.data(entries),"Orbit Metric0 Metric1","",tokens("ORBIT","Metric0","Metric1"));
        assertThat(result.limited()).isFalse();
        assertThat(result.concepts().stream().flatMap(c->c.targets().stream()).map(OntologyInquiry.Target::table).distinct()).containsExactlyInAnyOrder("ORBIT_0","ORBIT_1");
        assertThat(result.analysis().tokens()).extracting(Token::token).contains("ORBIT");
    }
    @Test void registeredExactLabelsOutrankIncidentalMentionsInDescriptions(){
        var data=fixtures.data(List.of(labelled("METRICS","Daily metrics","Revenue"),labelled("EVENTS","Other data about Revenue","Event")));
        var result=OntologyInquiry.search(data,"Show Revenue","",tokens("SHOW","REVENUE"));
        assertThat(result.concepts().getFirst().targets()).extracting(OntologyInquiry.Target::table).containsExactly("METRICS");
    }
    @Test void nativeStemsCanQualifySavedConceptsWithoutMatchingEmbeddedEnglishLetters(){
        var entries=List.of(labelled("APP_METRIC","데모나라 집계","Metric0"),labelled("APP_BIZ","데모나라 사업 집계","Metric1"),labelled("OTHER","Other app","Metric0"));
        var result=OntologyInquiry.search(fixtures.data(entries),"데모나라 Metric0 Metric1 for August","",tokens("데모나","Metric0","Metric1","FOR","AUGUST"));
        assertThat(result.concepts().stream().flatMap(c->c.targets().stream()).map(OntologyInquiry.Target::table).distinct()).containsExactlyInAnyOrder("APP_METRIC","APP_BIZ");
        assertThat(result.analysis().tokens()).extracting(Token::token).contains("FOR","AUGUST");
        var words=fixtures.data(List.of(labelled("NOISE","Information format","OTHER"),labelled("ACTIVE","Activity","AU")));
        var english=OntologyInquiry.search(words,"AU for August","",tokens("AU","FOR","AUGUST"));
        assertThat(english.concepts()).extracting(OntologyInquiry.Concept::term).containsExactly("AU");
    }
    @Test void unregisteredWordsRemainVisibleButDoNotCreateEvidence(){
        var data=fixtures.data(List.of(fixtures.entry("METRICS","Revenue","APPROVED",List.of())));
        String q="Show revenue for August 3, 2028";var analysis=tokens("SHOW","REVENUE","FOR","AUGUST");
        var result=OntologyInquiry.search(data,q,"",analysis);
        assertThat(result.question()).isEqualTo(q);assertThat(result.analysis()).isEqualTo(analysis);
        assertThat(result.concepts()).extracting(OntologyInquiry.Concept::term).containsExactly("REVENUE");
        assertThat(result.routes().getFirst().tables()).containsExactly("METRICS");
    }
    @Test void aRegisteredAugustIsNotDiscardedByAnEnglishStopListOrDateHeuristic(){
        var data=fixtures.data(List.of(fixtures.entry("SEASON","August","APPROVED",List.of())));
        var result=OntologyInquiry.search(data,"Show August", "", tokens("SHOW","AUGUST"));
        assertThat(result.concepts()).extracting(OntologyInquiry.Concept::term).containsExactly("AUGUST");
    }
    @Test void nativeTokensNeedNotOccurVerbatimAndNeverDependOnSpaceSplitting(){
        var data=fixtures.data(List.of(fixtures.entry("METRICS","売上","APPROVED",List.of())));
        String q="売上を見せて";var result=OntologyInquiry.search(data,q,"",tokens("売上"));
        assertThat(result.routes().getFirst().tables()).containsExactly("METRICS");assertThat(result.question()).isEqualTo(q);
        assertThat(OntologyInquiry.search(data,q,"").concepts()).isEmpty();
    }
    @Test void nativeTokenNumbersAreNotRemovedInTheApplication(){
        var data=fixtures.data(List.of(fixtures.entry("GAP","Partition","APPROVED",List.of())));
        var result=OntologyInquiry.search(data,"Show partition 2028", "", tokens("SHOW","Partition","2028"));
        assertThat(result.analysis().tokens()).extracting(Token::token).contains("2028");
        assertThat(result.concepts()).extracting(OntologyInquiry.Concept::term).doesNotContain("2028");
    }
    @Test void verifiedEmptyOntologySearchCanProceedAndExistingOrExpiredMatchesCannotBeOmitted(){
        var state=new SelectAiEvidence.State();var data=fixtures.data(List.of(fixtures.entry("METRICS","Revenue","APPROVED",List.of())));
        state.dataset("APP",false,()->data);var json=new JsonMapper();
        state.search("APP","SHOW unknown FOR AUGUST","",tokens("SHOW","unknown","FOR","AUGUST"));
        var empty=state.definitions("APP","SHOW unknown FOR AUGUST",List.of(),json);
        assertThat(empty.references()).isEmpty();assertThat(state.resolve(true,empty.hash(),empty.question())).isEqualTo(empty);
        assertThat(SelectAiTest.prompt(SelectAiTest.Action.SQL,empty.question(),"en",empty)).isEqualTo(SelectAiTest.prompt(SelectAiTest.Action.SQL,empty.question(),"en"));
        assertThatThrownBy(()->SelectAiTest.prompt(SelectAiTest.Action.SQL,"changed","en",empty)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->state.definitions("APP",empty.question(),List.of(),json)).isInstanceOf(AiAssistant.Failure.class);
        state.search("APP","revenue","",tokens("REVENUE"));
        assertThatThrownBy(()->state.definitions("APP","revenue",List.of(),json)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void oracleQueryEscapesAllTokensIncludingEnglishRequestWords(){
        assertThat(BusinessGlossary.textQuery(tokens("SHOW","FOR","AUGUST","AND").tokens())).isEqualTo("{SHOW} OR {FOR} OR {AUGUST} OR {AND}");
    }
}
