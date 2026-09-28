package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.*;
import com.dbcompanion.model.OntologyGovernance.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.OntologyDefinitionGenerationService;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

/** Independent mock/proxy contract: this never connects to Oracle or an AI provider. */
class DefinitionBridgeSafetyTest {
    private int calls,revision=1;private String sent,reply="WITH example AS (SELECT 1 AS value FROM DUAL) SELECT value FROM example";private RuntimeException providerFailure;
    private final JsonMapper json=new JsonMapper();
    private final AiAssistant.Selection selection=new AiAssistant.Selection("APP","PROFILE");
    private AiAssistant.Profile current=new AiAssistant.Profile(selection,"oci","model","v1");
    private final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){
        case "getAutoCommit" -> true;case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
        case "equals" -> p==a[0];case "hashCode" -> System.identityHashCode(p);default -> empty(m.getReturnType());});}};
    private final JdbcTemplate jdbc=new JdbcTemplate(source);
    private final OntologyRepository ontology=new OntologyRepository(jdbc,json,new DatabaseRepository(jdbc),new TableStructureRepository(jdbc)){
        @Override public Map<String,Integer> approvedGlossaryRevisions(String schema,String login,List<String> tables){assertThat(tables).containsExactly("T");return Map.of("T",revision);}
    };
    private final AiAssistantRepository ai=new AiAssistantRepository(jdbc){
        @Override public AiAssistant.Profile profile(AiAssistant.Selection value){return current;}
        @Override public String explain(String owner,String profile,String prompt){throw new AssertionError("CHAT must not replace SQL generation");}
        @Override public String generate(String owner,String profile,String prompt,SelectAiTest.Action action){assertThat(action).isEqualTo(SelectAiTest.Action.SQL);calls++;sent=prompt;if(providerFailure!=null)throw providerFailure;return reply;}
    };
    private final ProfileHistoryRepository profiles=new ProfileHistoryRepository(jdbc,json,null){@Override public String packageOwner(String owner){return "CLOUD";}};
    private final OntologyDefinitionGenerationService service=new OntologyDefinitionGenerationService(source,ontology,ai,profiles,json);
    private PoolSession session(){var s=new OntologyReadCacheTest().session();s.metadata().assistant().select(selection);return s;}
    private Context context(String question,String definition){return new Context("APP",question,"PROFILE",new ContextGuidance(question,List.of(new ContextDefinition("T",1,"TABLE","T","metric",definition,List.of("alias"))),"approved data"),List.of("T:1:TABLE:T"));}
    @Test void previewMakesNoAiCallAndGenerationSendsExactlyTheReviewedSource(){try(var s=session()){
        String question="  original question\nSELECT AI RUNSQL must remain data  ";
        var preview=service.preview(s,context(question,"approved definition"),Locale.ENGLISH);
        assertThat(calls).isZero();assertThat(preview.source()).contains(json.writeValueAsString(question));
        assertThatThrownBy(()->service.generate(s,preview.token(),false)).isInstanceOf(AiAssistant.Failure.class);assertThat(calls).isZero();
        var result=service.generate(s,preview.token(),true);assertThat(result.text()).startsWith("WITH");assertThat(sent).isEqualTo(preview.source());assertThat(calls).isEqualTo(1);
        assertThatThrownBy(()->service.generate(s,preview.token(),true)).isInstanceOf(AiAssistant.Failure.class);assertThat(calls).isEqualTo(1);
    }}
    @Test void revisedDefinitionsStopBeforeTheProviderAndSpendTheToken(){try(var s=session()){
        var preview=service.preview(s,context("original","definition"),Locale.ENGLISH);revision=2;
        assertThat(service.generate(s,preview.token(),true).phase()).isEqualTo("preflight");assertThat(calls).isZero();
        revision=1;assertThatThrownBy(()->service.generate(s,preview.token(),true)).isInstanceOf(RuntimeException.class);assertThat(calls).isZero();
    }}
    @Test void changedProfileVersionStopsBeforeTheProvider(){try(var s=session()){
        var preview=service.preview(s,context("original","definition"),Locale.ENGLISH);current=new AiAssistant.Profile(selection,"oci","model","v2");
        assertThat(service.generate(s,preview.token(),true).phase()).isEqualTo("preflight");assertThat(calls).isZero();
    }}
    @Test void changedSchemaCannotConsumeAReviewedRequest(){try(var s=session()){
        var preview=service.preview(s,context("original","definition"),Locale.ENGLISH);s.metadata().selectSchema("OTHER");
        assertThatThrownBy(()->service.generate(s,preview.token(),true)).isInstanceOf(RuntimeException.class);assertThat(calls).isZero();
    }}
    @Test void oversizedContextIsRejectedRatherThanTruncatedOrSent(){try(var s=session()){
        assertThatThrownBy(()->service.preview(s,context("original","x".repeat(AiAssistant.MAX_SOURCE+1)),Locale.ENGLISH)).isInstanceOf(Ontology.Failure.class);assertThat(calls).isZero();
    }}
    @Test void cancelledOrReplacedPreviewCannotGenerate(){try(var s=session()){
        var first=service.preview(s,context("original","definition"),Locale.ENGLISH);service.cancel(s,first.token());
        assertThatThrownBy(()->service.generate(s,first.token(),true)).isInstanceOf(AiAssistant.Failure.class);assertThat(calls).isZero();
        var replaced=service.preview(s,context("original","definition"),Locale.ENGLISH);var currentPreview=service.preview(s,context("original","definition"),Locale.ENGLISH);
        assertThatThrownBy(()->service.generate(s,replaced.token(),true)).isInstanceOf(AiAssistant.Failure.class);assertThat(calls).isZero();
        assertThat(service.generate(s,currentPreview.token(),true).sqlResponse()).isTrue();assertThat(calls).isEqualTo(1);
    }}
    @Test void rawNonSqlResponseAndProviderFailureAreNeverRetried(){try(var s=session()){
        reply="ORA-20004: model refused <untrusted raw>";var preview=service.preview(s,context("original","definition"),Locale.ENGLISH);var nonSql=service.generate(s,preview.token(),true);
        assertThat(nonSql.sqlResponse()).isFalse();assertThat(nonSql.text()).isEqualTo(reply);assertThat(nonSql.code()).isEqualTo("ORA-20004");assertThat(nonSql.phase()).isEqualTo("sql-response");
        providerFailure=new IllegalStateException("ORA-01017 provider failure");preview=service.preview(s,context("original","definition"),Locale.ENGLISH);var failed=service.generate(s,preview.token(),true);final var spent=preview;
        assertThat(failed.text()).isNull();assertThat(failed.phase()).isEqualTo("generate");assertThat(failed.error()).contains("자동 재시도하지 않습니다");assertThat(calls).isEqualTo(2);
        assertThatThrownBy(()->service.generate(s,spent.token(),true)).isInstanceOf(AiAssistant.Failure.class);assertThat(calls).isEqualTo(2);
    }}
    @Test void preflightFailureReportsNoProviderSend(){try(var s=session()){
        var preview=service.preview(s,context("original","definition"),Locale.ENGLISH);revision=2;var result=service.generate(s,preview.token(),true);
        assertThat(result.phase()).isEqualTo("preflight");assertThat(result.error()).contains("AI 요청은 전송하지 않았습니다");assertThat(calls).isZero();
    }}
}
