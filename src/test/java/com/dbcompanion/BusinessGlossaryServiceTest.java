package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.BusinessGlossary.*;
import com.dbcompanion.repository.BusinessGlossaryRepository;
import com.dbcompanion.service.BusinessGlossaryService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class BusinessGlossaryServiceTest {
    private static Connection connection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
        case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;
        default->m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
    });}
    final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){return connection();}};
    final JsonMapper json=new JsonMapper();
    final String id="11111111-1111-1111-1111-111111111111";
    Term term=new Term(id,1,"사업 AU",List.of("사업 활성 사용자"),"사업 기준 활성 사용자","BIZ_AU_FLAG = '1'",true,"v1");
    String table="READY",text="MISSING";int tokenCalls,installs,saves,phraseCalls,keyCalls;
    List<Target> nativeTargets=List.of(new Target("사업 AU","TERM",List.of(id)));
    final BusinessGlossaryRepository repository=new BusinessGlossaryRepository(new JdbcTemplate(source),json){
        @Override public String tableStatus(String owner){return table;}
        @Override public void requireTable(String owner){if(!table.equals("READY"))throw BusinessGlossary.stale();}
        @Override public String textStatus(String owner){return text;}
        @Override public List<Key> keys(String owner){keyCalls++;return List.of(new Key(id,term.revision(),term.term(),term.aliases()));}
        @Override public Term find(String owner,String key){assertThat(owner).isEqualTo("APP");assertThat(key).isEqualTo(id);return term;}
        @Override public List<Token> tokens(String question){tokenCalls++;return List.of(new Token("사업",1,6),new Token("AU",4,2));}
        @Override public List<Target> phraseTargets(String owner,String question,String query){phraseCalls++;assertThat(owner).isEqualTo("APP");assertThat(query).isEqualTo("{사업} OR {AU}");return nativeTargets;}
        @Override public void install(String owner,String operation){installs++;table="READY";}
        @Override public Term save(String owner,String key,long revision,Draft draft){saves++;return term;}
    };
    final BusinessGlossaryService service=new BusinessGlossaryService(source,repository,json,new com.dbcompanion.repository.BusinessGlossaryHistoryRepository(new JdbcTemplate(source),null,json){
        @Override public void require(String owner){}
        @Override public void append(String owner,Term before,Term after){}
    });
    private PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));return s;}
    @Test void exactSearchNeedsNeitherTextNorAProfileAndNeverInstalls(){try(var s=session()){
        var result=service.search(s,"2026-08-03 사업 AU를 알려줘","",false);
        assertThat(result.hits()).hasSize(1);assertThat(result.tokens()).isEmpty();assertThat(result.question()).startsWith("2026-08-03");assertThat(tokenCalls+installs+saves).isZero();
    }}
    @Test void unavailableTextIsExplicitFailureNotEmptyResultOrSilentFallback(){try(var s=session()){
        assertThatThrownBy(()->service.search(s,"사업 AU","P",true)).isInstanceOf(AiAssistant.Failure.class).hasMessageContaining("Oracle Text");assertThat(tokenCalls).isZero();
    }}
    @Test void nativeSearchShowsTokensAndOnlyUsesDbPhraseTargets(){try(var s=session()){
        text="READY";var result=service.search(s,"사업 AU","P",true);assertThat(result.hits()).hasSize(1);assertThat(result.tokens()).hasSize(2);assertThat(result.textQuery()).isEqualTo("{사업} OR {AU}");assertThat(result.targets().getFirst().expression()).isEqualTo("사업 AU");
        assertThat(phraseCalls).isEqualTo(1);assertThat(keyCalls).isZero();
    }}
    @Test void nativeRejectionIsNotOverriddenByJavaExactMatchingOrDescriptionHits(){try(var s=session()){
        text="READY";nativeTargets=List.of();var result=service.search(s,"사업 AU","P",true);
        assertThat(result.hits()).isEmpty();assertThat(result.targets()).isEmpty();assertThat(phraseCalls).isEqualTo(1);assertThat(keyCalls).isZero();
    }}
    @Test void dictionarySearchStillRequiresItsIndexWithoutInstallingOrRewritingQuestions(){try(var s=session()){
        assertThatThrownBy(()->service.search(s,"SHOW 사업 AU FOR AUGUST","P",true)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(tokenCalls).isZero();text="READY";
        var result=service.search(s,"SHOW 사업 AU FOR AUGUST","P",true);
        assertThat(result.mode()).isEqualTo("ORACLE_TEXT");
        assertThat(result.question()).isEqualTo("SHOW 사업 AU FOR AUGUST");assertThat(tokenCalls).isEqualTo(1);assertThat(installs+saves).isZero();
        text="CHECK_REQUIRED";assertThatThrownBy(()->service.search(s,"q","P",true)).isInstanceOf(AiAssistant.Failure.class);
    }}
    @Test void zeroMatchesCanProceedButMissingMatchesCannotBeSilentlyOmitted(){try(var s=session()){
        var empty=service.search(s,"Unknown request","P",false);
        var snapshot=service.resolve(s,"Unknown request","P",new Selection(true,empty.id(),List.of()));
        assertThat(snapshot.selected()).isEmpty();assertThat(BusinessGlossary.append("original",snapshot)).isEqualTo("original");
        var found=service.search(s,"사업 AU","P",false);
        assertThatThrownBy(()->service.resolve(s,"사업 AU","P",new Selection(true,found.id(),List.of()))).isInstanceOf(AiAssistant.Failure.class);
    }}
    @Test void onlyServerSelectedCurrentDefinitionsAreAccepted(){try(var s=session()){
        var found=service.search(s,"사업 AU","P",false);var selection=new Selection(true,found.id(),List.of(id));var snapshot=service.resolve(s,"사업 AU","P",selection);
        assertThat(snapshot.selected().getFirst().term()).isEqualTo(term);
        assertThatThrownBy(()->service.resolve(s,"사업 AU","OTHER",selection)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->service.resolve(s,"사업 AU","P",new Selection(true,found.id(),List.of("unknown")))).isInstanceOf(AiAssistant.Failure.class);
        term=new Term(id,2,term.term(),term.aliases(),"changed",term.criteria(),true,"v2");
        assertThatThrownBy(()->service.verify(s,snapshot)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->service.resolve(s,"사업 AU","P",selection)).isInstanceOf(AiAssistant.Failure.class);
    }}
    @Test void rdfDictionarySearchDoesNotInvalidateSelectAiDictionaryOrSqlPreparation(){try(var s=session()){
        String question="사업 AU";text="READY";
        var original=service.search(s,question,"P",true);
        var rdf=service.search(s,question,"",false);
        assertThat(service.interpret(s,rdf.id(),question,List.of(id)).terms()).containsExactly(term);
        var snapshot=service.resolve(s,question,"P",new Selection(true,original.id(),List.of(id)));
        var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"provider","model","v1");
        var test=s.metadata().aiTest();test.select(profile.selection());
        var prepared=test.prepare("APP",profile,SelectAiTest.Action.SQL,question,"ko",java.time.Instant.now(),null,null,snapshot);
        assertThat(prepared.glossary()).isEqualTo(snapshot);
        assertThat(prepared.preview().source()).contains(term.criteria());
        assertThat(installs+saves).isZero();
    }}
    @Test void selectAiDictionaryRefreshDoesNotInvalidatePendingRdfInterpretation(){try(var s=session()){
        var rdf=service.search(s,"사업 AU","",false);
        var original=service.search(s,"사업 AU","P",false);
        assertThat(service.interpret(s,rdf.id(),"사업 AU",List.of(id)).terms()).containsExactly(term);
        assertThat(service.resolve(s,"사업 AU","P",new Selection(true,original.id(),List.of(id))).selected()).hasSize(1);
    }}
    @Test void coexistingSearchesStillRejectChangedDefinitionsAndClearTogether(){try(var s=session()){
        var original=service.search(s,"사업 AU","P",false);
        var rdf=service.search(s,"사업 AU","",false);
        term=new Term(id,2,term.term(),term.aliases(),"changed",term.criteria(),true,"v2");
        assertThatThrownBy(()->service.resolve(s,"사업 AU","P",new Selection(true,original.id(),List.of(id)))).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->service.interpret(s,rdf.id(),"사업 AU",List.of(id))).isInstanceOf(AiAssistant.Failure.class);
        service.save(s,id,2,term.draft(),true);
        assertThatThrownBy(()->s.metadata().businessGlossary().resolve(original.id(),"APP","P","사업 AU",java.time.Instant.now())).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->s.metadata().businessGlossary().resolve(rdf.id(),"APP","","사업 AU",java.time.Instant.now())).isInstanceOf(AiAssistant.Failure.class);
    }}
    @Test void completeSearchResultsUpToThirtyResolveWithoutDroppingDefinitions(){
        for(int count:new int[]{11,30,31}){
            var terms=java.util.stream.IntStream.range(0,count).mapToObj(i->new Term(
                    "term-"+i,1,"Metric "+i,List.of(),"Metric definition","COUNT(*)",true,"v1")).toList();
            var multiple=new BusinessGlossaryRepository(new JdbcTemplate(source),json){
                @Override public void requireTable(String owner){}
                @Override public String textStatus(String owner){return "READY";}
                @Override public List<Key> keys(String owner){return List.of();}
                @Override public List<Token> tokens(String question){return List.of(new Token("metrics",1,7));}
                @Override public List<Target> phraseTargets(String owner,String question,String query){return terms.stream().map(t->new Target(t.term(),"TERM",List.of(t.id()))).toList();}
                @Override public Term find(String owner,String key){return terms.stream().filter(t->t.id().equals(key)).findFirst().orElseThrow();}
            };
            var bounded=new BusinessGlossaryService(source,multiple,json);
            try(var s=session()){
                var found=bounded.search(s,"metrics","P",true);
                var selection=new Selection(true,found.id(),found.hits().stream().map(h->h.term().id()).toList());
                if(count<=30){
                    var snapshot=bounded.resolve(s,"metrics","P",selection);
                    assertThat(snapshot.selected()).extracting(Hit::term).containsExactlyElementsOf(terms);
                    assertThatThrownBy(()->bounded.resolve(s,"metrics","P",new Selection(true,found.id(),selection.termIds().subList(0,count-1))))
                            .isInstanceOf(AiAssistant.Failure.class);
                }else{
                    assertThat(found.more()).isTrue();
                    assertThatThrownBy(()->bounded.resolve(s,"metrics","P",selection)).isInstanceOf(AiAssistant.Failure.class);
                }
            }
        }
    }
    @Test void writesRequireConsentAndSetupTokenIsSingleUse(){try(var s=session()){
        assertThatThrownBy(()->service.save(s,null,0,term.draft(),false)).isInstanceOf(AiAssistant.Failure.class);assertThat(saves).isZero();
        assertThatThrownBy(()->service.setupPreview(s,"TABLE")).isInstanceOf(AiAssistant.Failure.class);
        table="MISSING";var setup=service.setupPreview(s,"TABLE");assertThat(installs).isZero();
        assertThatThrownBy(()->service.setup(s,setup.token(),false)).isInstanceOf(AiAssistant.Failure.class);service.setup(s,setup.token(),true);
        assertThat(installs).isEqualTo(1);assertThatThrownBy(()->service.setup(s,setup.token(),true)).isInstanceOf(AiAssistant.Failure.class);
    }}
    @Test void savingInvalidatesPriorSearchAndDisabledTermsCannotBeSent(){try(var s=session()){
        var found=service.search(s,"사업 AU","P",false);service.save(s,id,1,term.draft(),true);
        assertThatThrownBy(()->service.resolve(s,"사업 AU","P",new Selection(true,found.id(),List.of(id)))).isInstanceOf(AiAssistant.Failure.class);
        var fresh=service.search(s,"사업 AU","P",false);term=new Term(id,1,term.term(),term.aliases(),term.definition(),term.criteria(),false,"v1");
        assertThatThrownBy(()->service.resolve(s,"사업 AU","P",new Selection(true,fresh.id(),List.of(id)))).isInstanceOf(AiAssistant.Failure.class);
    }}
}
