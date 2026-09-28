package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.repository.DatabaseRepository;
import com.dbcompanion.repository.OntologyRepository;
import com.dbcompanion.repository.TableStructureRepository;
import com.dbcompanion.service.OntologyGovernanceService;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

/** Bounded JSON-projection glossary tests; repository methods are synthetic and never contact Oracle. */
class GlossaryProjectionServiceTest {
    int projections,revisionReads,relationshipReads,textAvailabilityReads,textReads,textInstalls,textSyncs;
    List<OntologyRepository.GlossaryProjection> rows=List.of();
    Map<String,Integer> revisions=Map.of("T",1);
    Map<String,Integer> revisionsAfterTextSearch;
    OntologyRepository.TextSearchAvailability textAvailability=new OntologyRepository.TextSearchAvailability("READY","test");boolean textThrows,writeThrows;String profileVersion="v1";
    List<OntologyRepository.TextProjection> textRows=List.of();
    final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){
        case "getAutoCommit" -> true; case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
        case "equals" -> p==a[0]; case "hashCode" -> System.identityHashCode(p); default -> empty(m.getReturnType());});}};
    final JdbcTemplate jdbc=new JdbcTemplate(source);
    final OntologyRepository repository=new OntologyRepository(jdbc,new JsonMapper(),new DatabaseRepository(jdbc),new TableStructureRepository(jdbc)){
        @Override public void require(String schema,String login) { }
        @Override public List<OntologyRepository.GlossaryProjection> glossary(String schema,String login,List<String> tables){projections++;return rows;}
        @Override public Map<String,Integer> approvedGlossaryRevisions(String schema,String login,List<String> tables){revisionReads++;return revisionsAfterTextSearch!=null&&revisionReads>=2?revisionsAfterTextSearch:revisions;}
        @Override public List<Ontology.Entry> relationshipEntries(String schema,String login){relationshipReads++;return List.of();}
        @Override public OntologyRepository.TextSearchAvailability textSearchAvailability(String schema,String login){textAvailabilityReads++;return textAvailability;}
        @Override public List<OntologyRepository.TextProjection> textSearch(String schema,String login,String profile,Map<String,Integer> approved,String question,int limit,List<OntologyRepository.TextCacheRow> current){textReads++;if(textThrows)throw new org.springframework.dao.DataAccessResourceFailureException("synthetic");return textRows;}
        @Override public String textProfileVersion(String schema,String login,String profile){return profileVersion;}
        @Override public List<String> textActivationCheck(String schema,String login,String profile,String operation,List<OntologyRepository.TextCacheRow> candidates){return List.of("synthetic preflight");}
        @Override public void installText(String schema,String login,String profile){textInstalls++;}
        @Override public void syncText(String schema,String login,String profile,List<OntologyRepository.TextCacheRow> rows){textSyncs++;if(writeThrows)throw new org.springframework.dao.DataAccessResourceFailureException("synthetic uncertain write");assertThat(rows).isNotEmpty();assertThat(rows).allSatisfy(row->assertThat(row.searchText()).contains(row.term()));}
    };
    final OntologyGovernanceService service=new OntologyGovernanceService(source,repository);
    private PoolSession session(){return new OntologyReadCacheTest().session();}
    private static OntologyRepository.GlossaryProjection row(String table,int revision,String concept,String columns,String mappings){return new OntologyRepository.GlossaryProjection(table,revision,concept,"definition",columns,mappings);}

    @Test void unscopedSearchReturnsScopeRequiredWithoutAnyRepositoryRead(){try(var session=session()){
        var result=service.search(session,"APP","needle");
        assertThat(result.mode()).isEqualTo("SCOPE_REQUIRED");assertThat(result.capability()).isEqualTo("NOT_QUERIED");
        assertThat(projections+revisionReads+relationshipReads).isZero();
    }}

    @Test void laterExactOutranksOneHundredEarlierAliasesAndMappingsAreProjected(){
        var values=new ArrayList<Map<String,Object>>();for(int i=0;i<100;i++)values.add(Map.of("id","A"+i,"label","term"+i,"description","", "aliases",List.of("needle")));
        values.add(Map.of("id","EXACT","label","needle","description","exact", "aliases",List.of()));
        rows=List.of(row("T",1,"",null,new JsonMapper().writeValueAsString(values)));
        try(var session=session()){
            var result=service.search(session,"APP","needle",List.of("T"));
            assertThat(result.hits()).isNotEmpty();assertThat(result.hits().getFirst().matchKind()).isEqualTo("EXACT");
            assertThat(result.hits().getFirst().sourceId()).isEqualTo("EXACT");assertThat(result.partial()).isTrue();
        }
    }

    @Test void sameNamedColumnsRemainDistinctAndMalformedProjectionIsNotEmptySuccess(){try(var session=session()){
        rows=List.of(row("T",1,"","{\"A\":{\"description\":\"one\",\"definition\":{\"label\":\"customer\",\"aliases\":[]}},\"B\":{\"description\":\"two\",\"definition\":{\"label\":\"customer\",\"aliases\":[]}}}",null));
        assertThat(service.search(session,"APP","customer",List.of("T")).hits()).extracting("sourceId").containsExactly("A","B");
        rows=List.of(row("T",1,"","[]",null));
        assertThatThrownBy(()->service.search(session,"APP","customer",List.of("T"))).isInstanceOf(Ontology.Failure.class);
    }}

    @Test void consumeBindsScopeProfileAndCurrentApprovedRevision(){try(var session=session()){
        rows=List.of(row("T",1,"needle",null,null));
        var preview=service.context(session,"APP","needle",List.of("T"),"APP.PROFILE");
        revisions=Map.of("T",2);
        assertThatThrownBy(()->service.consume(session,"APP","needle",List.of("T"),"APP.PROFILE",preview.token(),List.of(preview.hits().getFirst().identity())))
                .isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->service.consume(session,"APP","needle",List.of("T"),"APP.PROFILE",preview.token(),List.of())).isInstanceOf(Ontology.Failure.class);
    }}
    @Test void consumeSerializesDefinitionsAsDataAndKeepsOriginalQuestionSeparate(){try(var session=session()){
        rows=List.of(row("T",1,"needle",null,null));var preview=service.context(session,"APP","what is needle?",List.of("T"),"APP.PROFILE");
        var context=service.consume(session,"APP","what is needle?",List.of("T"),"APP.PROFILE",preview.token(),List.of(preview.hits().getFirst().identity()));
        assertThat(context.question()).isEqualTo("what is needle?");assertThat(context.guidance().originalQuestion()).isEqualTo("what is needle?");
        assertThat(context.guidance().definitions()).singleElement().extracting("term").isEqualTo("needle");assertThat(context.guidance().limitation()).contains("not connected to an AI");
    }}
    @Test void textSearchIsScopedAndRevalidatesSourceIdentity(){try(var session=session()){
        rows=List.of(row("T",1,"approved term",null,null));textRows=List.of(new OntologyRepository.TextProjection("T",1,"TABLE","T","cached term","cached definition","[\"cached\"]",9));
        var result=service.textSearch(session,"APP","needle",List.of("T"),"APP.PROFILE");
        assertThat(result.mode()).isEqualTo("ORACLE_TEXT");assertThat(result.status()).isEqualTo("AVAILABLE");assertThat(result.lexerStatus()).isEqualTo("UNCONFIRMED");assertThat(result.hits()).singleElement().extracting("sourceId").isEqualTo("T");
        assertThat(result.hits()).singleElement().extracting("term","definition","aliases").containsExactly("approved term","definition",List.of());
        assertThat(textReads).isEqualTo(1);assertThat(relationshipReads).isZero();
    }}
    @Test void textSearchDiscardsCandidatesWhenApprovedRevisionChangesDuringLookup(){try(var session=session()){
        rows=List.of(row("T",1,"approved term",null,null));textRows=List.of(new OntologyRepository.TextProjection("T",1,"TABLE","T","cached","wrong","[]",9));revisionsAfterTextSearch=Map.of("T",2);
        var result=service.textSearch(session,"APP","needle",List.of("T"),"APP.PROFILE");
        assertThat(result.status()).isEqualTo("UNCONFIRMED");assertThat(result.hits()).isEmpty();assertThat(result.limitation()).contains("revisions changed");
    }}
    @Test void textSearchDoesNotTreatMissingObjectsOrQueryFailureAsExactAlias(){try(var session=session()){
        textAvailability=new OntologyRepository.TextSearchAvailability("UNAVAILABLE","not visible");
        var unavailable=service.textSearch(session,"APP","needle",List.of("T"),"APP.PROFILE");
        assertThat(unavailable.status()).isEqualTo("UNAVAILABLE");assertThat(unavailable.hits()).isEmpty();assertThat(textReads).isZero();
        textAvailability=new OntologyRepository.TextSearchAvailability("READY","ready");textRows=List.of(new OntologyRepository.TextProjection("T",1,"COLUMN","missing","needle","","[]",1));
        var unconfirmed=service.textSearch(session,"APP","needle",List.of("T"),"APP.PROFILE");
        assertThat(unconfirmed.status()).isEqualTo("UNCONFIRMED");assertThat(unconfirmed.mode()).isEqualTo("ORACLE_TEXT");assertThat(unconfirmed.hits()).isEmpty();
    }}
    @Test void textQueryPrivilegeFailureIsUnconfirmedRatherThanAnEmptySuccessfulSearch(){try(var session=session()){
        rows=List.of(row("T",1,"approved",null,null));textThrows=true;var result=service.textSearch(session,"APP","needle",List.of("T"),"APP.PROFILE");
        assertThat(result.status()).isEqualTo("UNCONFIRMED");assertThat(result.hits()).isEmpty();assertThat(result.limitation()).contains("does not establish");
    }}
    @Test void textSearchRejectsOverlongQuestionAndMissingProfileWithoutFallback(){try(var session=session()){
        assertThatThrownBy(()->service.textSearch(session,"APP","가".repeat(257),List.of("T"),"APP.PROFILE")).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->service.textSearch(session,"APP","needle",List.of("T"),"")).isInstanceOf(Ontology.Failure.class);
        var noScope=service.textSearch(session,"APP","needle",List.of(),"APP.PROFILE");
        assertThat(noScope.status()).isEqualTo("SCOPE_REQUIRED");assertThat(noScope.hits()).isEmpty();assertThat(textReads).isZero();
    }}
    @Test void changedProfileOrSameRevisionPayloadInvalidatesActivationWithoutWriting(){try(var session=session()){
        rows=List.of(row("T",1,"approved",null,null));
        var first=service.textActivationPreview(session,"APP","APP.PROFILE",List.of("T"),"SYNC");profileVersion="v2";
        assertThatThrownBy(()->service.activateText(session,"APP","APP.PROFILE",List.of("T"),"SYNC",first.token(),true)).isInstanceOf(Ontology.Failure.class);
        var second=service.textActivationPreview(session,"APP","APP.PROFILE",List.of("T"),"SYNC");rows=List.of(row("T",1,"changed",null,null));
        assertThatThrownBy(()->service.activateText(session,"APP","APP.PROFILE",List.of("T"),"SYNC",second.token(),true)).isInstanceOf(Ontology.Failure.class);
        assertThat(textSyncs).isZero();
    }}
    @Test void unknownWriteOutcomeBlocksSessionRetryAndNeverClaimsRollback(){try(var session=session()){
        rows=List.of(row("T",1,"approved",null,null));
        var preview=service.textActivationPreview(session,"APP","APP.PROFILE",List.of("T"),"SYNC");writeThrows=true;
        var result=service.activateText(session,"APP","APP.PROFILE",List.of("T"),"SYNC",preview.token(),true);
        assertThat(result.status()).isEqualTo("CHECK_REQUIRED");
        assertThatThrownBy(()->service.textActivationPreview(session,"APP","APP.PROFILE",List.of("T"),"SYNC")).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->service.activateText(session,"APP","APP.PROFILE",List.of("T"),"SYNC",preview.token(),true)).isInstanceOf(Ontology.Failure.class);
        assertThat(textSyncs).isEqualTo(1);
    }}
    @Test void textActivationRequiresOneTimePreviewConsentAndCurrentApprovedRevision(){try(var session=session()){
        rows=List.of(row("T",1,"approved",null,null));var preview=service.textActivationPreview(session,"APP","APP.PROFILE",List.of("T"),"SYNC");
        assertThat(preview.candidateCount()).isEqualTo(1);assertThat(preview.impacts()).anyMatch(v->v.contains("no DELETE"));assertThat(textSyncs).isZero();
        revisions=Map.of("T",2);final var stalePreview=preview;assertThatThrownBy(()->service.activateText(session,"APP","APP.PROFILE",List.of("T"),"SYNC",stalePreview.token(),true)).isInstanceOf(Ontology.Failure.class);assertThat(textSyncs).isZero();
        revisions=Map.of("T",1);preview=service.textActivationPreview(session,"APP","APP.PROFILE",List.of("T"),"SYNC");final var unconsentedPreview=preview;assertThatThrownBy(()->service.activateText(session,"APP","APP.PROFILE",List.of("T"),"SYNC",unconsentedPreview.token(),false)).isInstanceOf(Ontology.Failure.class);assertThat(textSyncs).isZero();
        preview=service.textActivationPreview(session,"APP","APP.PROFILE",List.of("T"),"SYNC");assertThat(service.activateText(session,"APP","APP.PROFILE",List.of("T"),"SYNC",preview.token(),true).status()).isEqualTo("COMPLETED");assertThat(textSyncs).isEqualTo(1);
    }}
}
