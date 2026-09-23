package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.*;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

class OntologyScopeServiceTest {
    int allReads,selectedReads,aiCalls,installs;String store="READY";final Map<String,JsonNode> saved=new LinkedHashMap<>();
    final OntologyPipelineTest fixtures=new OntologyPipelineTest();final JsonMapper json=new JsonMapper();
    final SessionDataSource source=new SessionDataSource(){
        @Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);default->empty(m.getReturnType());
        });}
    };
    final JdbcTemplate jdbc=new JdbcTemplate(source);
    final OntologyRepository repository=new OntologyRepository(jdbc,json,new DatabaseRepository(jdbc),new TableStructureRepository(jdbc)){
        @Override public List<Ontology.Entry> relationshipEntries(String schema,String login){allReads++;throw new AssertionError("Whole-catalog read forbidden");}
        @Override public List<Ontology.Entry> relationshipEntries(String schema,String login,List<String> tables){selectedReads++;assertThat(OntologyScope.names(tables)).containsExactly("CUSTOMER","ORDERS");return List.of(fixtures.customer(),fixtures.orders());}
    };
    final AiAssistantRepository ai=new AiAssistantRepository(jdbc){
        @Override public AiAssistant.Profile profile(AiAssistant.Selection selection){return fixtures.profile;}
        @Override public String explain(String owner,String profile,String prompt){aiCalls++;throw new AssertionError("No AI allowed");}
    };
    final AppRecordRepository records=new AppRecordRepository(jdbc,json){
        @Override public String status(String schema,String login){return store;}
        @Override public void require(String schema,String login){if(!store.equals("READY"))throw new Ontology.Failure(409,"archive.notReady");}
        @Override public void begin(String schema,String id,String type,JsonNode payload){assertThat(type).isEqualTo(OntologyScope.TYPE);saved.put(id,payload);}
        @Override public boolean finish(String schema,String id,String state,JsonNode payload){assertThat(state).isEqualTo("SUCCEEDED");return saved.containsKey(id);}
        @Override public void install(String schema,String login){installs++;store="READY";}
    };
    final OntologyService ontology=new OntologyService(source,repository,ai,null,json){
        @Override public Ontology.Catalog catalog(PoolSession s,String schema,boolean refresh){return new Ontology.Catalog("READY",false,List.of(new TableInfo("CUSTOMER","")),List.of(new Ontology.Summary("CUSTOMER",1,"DRAFT","APP","now")),"now");}
    };
    final OntologyScopeService service=new OntologyScopeService(source,ontology,null,records,json);
    PoolSession session(){return new OntologyReadCacheTest().session();}
    @Test void previewLoadsOnlyExplicitTablesAndDoesNotCallAiOrModifyProfiles(){try(var s=session()){
        s.metadata().assistant().select(fixtures.profile.selection());var pipeline=new OntologyPipelineService(source,repository,ai,null,null,json);
        var result=pipeline.preview(s,"APP",List.of("ORDERS","CUSTOMER"));assertThat(result.tables()).isEqualTo(2);assertThat(result.budget().reason()).isEmpty();
        assertThat(selectedReads).isEqualTo(1);assertThat(allReads).isZero();assertThat(aiCalls).isZero();
        assertThat(s.metadata().ontology().pipeline().references()).extracting("table").containsExactly("CUSTOMER","ORDERS");
    }}
    @Test void savingAddsImmutableMetadataOnlyCopiesAndSurvivesNewSession(){
        try(var s=session()){service.save(s,"APP","업무",List.of("ORDERS","CUSTOMER"));service.save(s,"APP","업무",List.of("CUSTOMER"));}
        assertThat(saved).hasSize(2);for(var payload:saved.values()){assertThat(payload.size()).isEqualTo(5);assertThat(payload.path("database").asString()).isEqualTo("DB");assertThat(payload.toString()).doesNotContain("password","rdf","model");}
        try(var s=session()){assertThat(service.options(s,"APP").canSave()).isTrue();assertThat(saved).hasSize(2);}
        assertThat(installs).isZero();
    }
    @Test void wrongSchemaAndNonownerCannotSaveOrInstall(){try(var s=session()){
        assertThatThrownBy(()->service.save(s,"OTHER","set",List.of("A"))).isInstanceOf(Ontology.Failure.class);
        s.metadata().selectSchema("OTHER");assertThatThrownBy(()->service.save(s,"OTHER","set",List.of("A"))).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->service.install(s,"OTHER",true)).isInstanceOf(Ontology.Failure.class);assertThat(installs).isZero();assertThat(saved).isEmpty();
    }}
    @Test void missingStoreDoesNotBlockSelectionOrTriggerDdlAndInstallRequiresConsent(){try(var s=session()){
        store="MISSING";var options=service.options(s,"APP");assertThat(options.tables()).hasSize(1);assertThat(options.canSave()).isFalse();assertThat(options.installSql()).startsWith("CREATE TABLE");assertThat(installs).isZero();
        assertThatThrownBy(()->service.install(s,"APP",false)).isInstanceOf(Ontology.Failure.class);assertThat(installs).isZero();
        assertThat(service.install(s,"APP",true).canSave()).isTrue();assertThat(installs).isEqualTo(1);
    }}
}
