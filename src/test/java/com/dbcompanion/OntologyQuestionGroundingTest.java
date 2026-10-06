package com.dbcompanion;
import com.dbcompanion.model.BusinessGlossary.*;
import com.dbcompanion.service.OntologyQuestionGrounding;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class OntologyQuestionGroundingTest {
    @Test void longDefinitionsRemainEvidenceWithoutBecomingSearchTokens(){
        String definition=java.util.stream.IntStream.range(0,100).mapToObj(i->"definitionWord"+i).collect(java.util.stream.Collectors.joining(" "));
        var evidence=OntologyQuestionGrounding.resolve(search(List.of(term("a",definition))),List.of("a"));
        var original=new Analysis("ORACLE_TEXT",List.of(new Token("AU",11,2),new Token("2026-01-01",0,10)),"ko","POLICY","LEXER");
        var result=OntologyQuestionGrounding.searchTerms(original,evidence,List.of("AU","active users"));
        assertThat(result.tokens()).extracting(Token::token).containsExactly("AU","2026-01-01","active users");
        assertThat(result.tokens().getFirst()).isEqualTo(original.tokens().getFirst());
        assertThat(result.language()).isEqualTo("ko");assertThat(result.policy()).isEqualTo("POLICY");assertThat(result.lexer()).isEqualTo("LEXER");
        assertThat(com.dbcompanion.model.BusinessGlossary.textQuery(result.tokens())).doesNotContain("definitionWord","count distinct user");
        assertThat(evidence.interpreted()).contains(definition,"count distinct user");
        assertThat(evidence.original()).isEqualTo("2026-01-01 AU 100 이상");
    }
    @Test void originalSearchBudgetIsStillEnforcedWithoutSilentTruncation(){
        var tokens=java.util.stream.IntStream.range(0,65).mapToObj(i->new Token("word"+i,i,1)).toList();
        var result=OntologyQuestionGrounding.searchTerms(new Analysis("ORACLE_TEXT",tokens),OntologyQuestionGrounding.resolve(search(List.of()),List.of()),List.of());
        assertThat(result.tokens()).hasSize(65);
        assertThatThrownBy(()->com.dbcompanion.model.BusinessGlossary.textQuery(result.tokens())).isInstanceOf(RuntimeException.class);
    }
    Term term(String id,String definition){return new Term(id,1,"AU",List.of(),definition,"count distinct user",true,"now");}
    Search search(List<Term> terms){return new Search("s","APP","","2026-01-01 AU 100 이상","EXACT_ALIAS",terms.isEmpty()?List.of():List.of(new Target("AU","TERM",terms.stream().map(Term::id).toList())),List.of(),"",terms.stream().map(t->new Hit(t,"TERM",List.of("AU"))).toList(),false,Instant.now().plusSeconds(60));}
    @Test void preservesOriginalAndAttachesDefinition(){var r=OntologyQuestionGrounding.resolve(search(List.of(term("a","active users"))),List.of("a"));assertThat(r.original()).isEqualTo("2026-01-01 AU 100 이상");assertThat(r.interpreted()).startsWith(r.original()).contains("active users","count distinct user");}
    @Test void ambiguityRequiresExactlyOneMeaning(){var s=search(List.of(term("a","active"),term("b","authorized")));assertThatThrownBy(()->OntologyQuestionGrounding.resolve(s,List.of())).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->OntologyQuestionGrounding.resolve(s,List.of("a","b"))).isInstanceOf(RuntimeException.class);assertThat(OntologyQuestionGrounding.resolve(s,List.of("b")).terms()).extracting(Term::id).containsExactly("b");}
    @Test void rejectsForgedAndDuplicateIds(){var s=search(List.of(term("a","active")));assertThatThrownBy(()->OntologyQuestionGrounding.resolve(s,List.of("fake"))).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->OntologyQuestionGrounding.resolve(s,List.of("a","a"))).isInstanceOf(RuntimeException.class);}
    @Test void noMatchesLeavesQuestionUntouched(){var s=search(List.of());assertThat(OntologyQuestionGrounding.resolve(s,List.of()).interpreted()).isEqualTo(s.question());}
}
