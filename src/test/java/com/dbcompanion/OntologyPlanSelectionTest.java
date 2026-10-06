package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.*;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

class OntologyPlanSelectionTest {
    @Test void ontologyQuestionRunsRdfRetrievalWithoutPlanOrCandidateSelection(){try(var s=session()){
        var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","LLM"),"oci","model","v1");
        var ai=new AiAssistantRepository(new JdbcTemplate(source)){
            @Override public AiAssistant.Profile profile(AiAssistant.Selection selection){return profile;}
            @Override public String explain(String owner,String name,String prompt){
                assertThat(prompt).contains("RDF ontology metadata","originalQuestion","dictionaryEvidence");
                return "{\"summary\":\"Find the source properties for the unreviewed concept\",\"concepts\":[\"unreviewed\"],\"questions\":[]}";
            }
        };
        var profiles=new ProfileHistoryRepository(new JdbcTemplate(source),json,null){@Override public String packageOwner(String owner){return "SYS";}};
        var service=new OntologyQueryService(source,repository,ai,profiles,null,null,json);
        var ground=new OntologyQuestionGrounding.Result("original business question","original business question",List.of());
        var glossary=new BusinessGlossaryService(source,null,json){
            @Override public OntologyQuestionGrounding.Result interpret(PoolSession session,String id,String question,List<String> ids){return ground;}
            @Override public void verifyTerms(PoolSession session,List<BusinessGlossary.Term> terms){}
        };
        org.springframework.test.util.ReflectionTestUtils.setField(service,"glossary",glossary);
        s.metadata().assistant().select(profile.selection());
        var preview=service.interpretPreview(s,"APP",ground.original(),"",QuestionLanguage.EN,"dictionary",List.of(),"");
        assertThat(preview.request().source()).doesNotContain("candidateRelations","routes","availableSources");
        var outcome=service.assistantGenerate(s,preview.request().token(),true);
        assertThat(outcome.plan()).isNull();assertThat(outcome.result().rdf().hits()).extracting(OntologyRdfSearch.Hit::table).containsExactly("C");
        assertThat(outcome.result().search().question()).isEqualTo(ground.original());
        assertThat(outcome.result().rdf().question()).isEqualTo(outcome.interpretation().summary());
        assertThat(outcome.result().search().routes()).isEmpty();assertThat(outcome.result().proposals()).isEmpty();
        assertThat(outcome.result().rdf().hits().getFirst().state()).isEqualTo("DRAFT");
    }}
    @Test void generationReturnsDraftRecommendationButExecutionSelectionStillRejectsIt(){try(var s=session()){
        var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","LLM"),"oci","model","v1");
        var ai=new AiAssistantRepository(new JdbcTemplate(source)){
            @Override public AiAssistant.Profile profile(AiAssistant.Selection selection){return profile;}
            @Override public String explain(String owner,String name,String prompt){return "```json\n{\"mode\":\"INDEPENDENT\",\"routeId\":null,\"tables\":[\"C\"],\"candidates\":[],\"reason\":\"aggregate independently\",\"questions\":[]}\n```";}
        };
        var profiles=new ProfileHistoryRepository(new JdbcTemplate(source),json,null){@Override public String packageOwner(String owner){return "SYS";}};
        var service=new OntologyQueryService(source,repository,ai,profiles,null,null,json);
        var assistant=s.metadata().assistant();assistant.select(profile.selection());
        var preview=assistant.prepare("APP",profile,"PLAN","{}",false,"ko",java.time.Instant.now(),"ontology-assistant");
        s.metadata().inquiry().aiStep(new OntologyQueryService.AiStep(preview.token(),"PLAN","APP","",QuestionLanguage.KO,"",null,id(s)));
        var outcome=service.assistantGenerate(s,preview.token(),true);
        assertThat(outcome.plan().tables()).containsExactly("C");assertThat(outcome.plan().mode()).isEqualTo("INDEPENDENT");
        assertThatThrownBy(()->service.applyPlan(s,id(s),"INDEPENDENT","",List.of("C"),List.of())).isInstanceOf(Failure.class);
        assertThat(entries.get(2).state()).isEqualTo("DRAFT");
        assertThatThrownBy(()->service.assistantGenerate(s,preview.token(),true)).isInstanceOf(RuntimeException.class);
    }}
    final OntologyInquiryTest fixture=new OntologyInquiryTest();
    final List<Entry> entries=List.of(fixture.entry("A","standard","APPROVED",List.of()),fixture.entry("B","business","APPROVED",List.of()),fixture.entry("C","unreviewed","DRAFT",List.of()));
    boolean changed;
    final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){
        case "getAutoCommit" -> true;case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
        case "createStatement" -> proxy(java.sql.Statement.class,(sp,sm,sa)->empty(sm.getReturnType()));
        case "equals" -> p==a[0];case "hashCode" -> System.identityHashCode(p);default -> empty(m.getReturnType());});}};
    final JsonMapper json=new JsonMapper();
    final OntologyRepository repository=new OntologyRepository(new JdbcTemplate(source),json,null,null){
        @Override public void require(String schema,String login){assertThat(schema).isEqualTo(login);}
        @Override public Entry entry(String schema,String name,int revision){return changed?null:entries.stream().filter(e->e.document().source().table().equals(name)).findFirst().orElse(null);}
    };
    final OntologyQueryService service=new OntologyQueryService(source,repository,null,null,null,null,json);
    final OntologyRelations.Relation candidate=new OntologyRelations.Relation("C1","A","APP","B",List.of("CODE"),List.of("CODE"),"STALE","AI","candidate","unverified equality",List.of(),null,null);
    PoolSession session(){
        var s=new OntologyReadCacheTest().session();var state=s.metadata().inquiry();var data=fixture.data(entries);state.dataset("APP",false,()->data);
        var search=OntologyInquiry.search(data,"standard and business","A");state.remember(search);
        var grounding=new OntologyQuestionGrounding.Result(search.question(),search.question(),List.of());state.grounding(grounding);
        state.grounded(new OntologyQueryService.GroundedSearch(search,grounding,List.of(candidate),null));return s;
    }
    String id(PoolSession s){return s.metadata().inquiry().grounded().search().id();}
    @Test void independentSelectionHasNoJoinAuthorizationAndPreservesCandidateState(){try(var s=session()){
        var selected=service.applyPlan(s,id(s),"INDEPENDENT","",List.of("A","B"),List.of("C1"));
        assertThat(selected.routes()).hasSize(1);assertThat(selected.routes().getFirst().id()).isEqualTo("INDEPENDENT");
        assertThat(selected.routes().getFirst().relations()).isEmpty();assertThat(selected.evidence()).allMatch(e->e.kind().equals("DEFINITION")&&e.usable());
        assertThat(s.metadata().inquiry().reviewedCandidates()).containsExactly(candidate);assertThat(candidate.status()).isEqualTo("STALE");
        var scope=ReviewedSql.scope(selected,OntologyInquiry.selected(selected,s.metadata().inquiry().data()));
        assertThat(scope.relations()).isEmpty();
        assertThat(ReviewedSql.check("SELECT COUNT(DISTINCT a.CODE) AS n FROM A a UNION ALL SELECT COUNT(DISTINCT b.CODE) AS n FROM B b",scope).tables()).containsExactly("A","B");
        assertThatThrownBy(()->ReviewedSql.check("SELECT a.CODE FROM A a JOIN B b ON a.CODE=b.CODE",scope)).isInstanceOf(Failure.class);
    }}
    @Test void forgedCandidatesUnapprovedSourcesAndStaleDefinitionsAreBlocked(){try(var s=session()){
        String id=id(s);
        assertThatThrownBy(()->service.applyPlan(s,id,"INDEPENDENT","",List.of("A"),List.of("FAKE"))).isInstanceOf(Failure.class);
        assertThatThrownBy(()->service.applyPlan(s,id,"INDEPENDENT","",List.of("A","C"),List.of())).isInstanceOf(Failure.class);
        assertThatThrownBy(()->service.applyPlan(s,id,"INDEPENDENT","",List.of("UNKNOWN"),List.of())).isInstanceOf(Failure.class);
        changed=true;assertThatThrownBy(()->service.applyPlan(s,id,"INDEPENDENT","",List.of("A","B"),List.of())).isInstanceOf(Failure.class);
    }}
}
