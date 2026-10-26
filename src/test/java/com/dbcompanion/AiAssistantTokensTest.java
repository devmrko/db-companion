package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.AiAssistantService;
import java.sql.Connection;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

class AiAssistantTokensTest {
    private final AiAssistant.Selection selection=new AiAssistant.Selection("APP","PROFILE");
    private String value,version="v1";
    private int writes,beforeCount,afterCount;
    private boolean archiveAvailable=true;
    private final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){
        case "getAutoCommit" -> true;case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
        case "equals" -> p==a[0];case "hashCode" -> System.identityHashCode(p);default -> empty(m.getReturnType());});}};
    private final JdbcTemplate jdbc=new JdbcTemplate(source);
    private final AiAssistantRepository repository=new AiAssistantRepository(jdbc){
        @Override public AiAssistant.Profile profile(AiAssistant.Selection selected){assertThat(selected).isEqualTo(selection);return new AiAssistant.Profile(selected,"oci","model",version);}
        @Override public String maxTokens(String name){return value;}
        @Override public void saveMaxTokens(String owner,String name,int limit){assertThat(owner).isEqualTo("CLOUD");writes++;value=Integer.toString(limit);}
    };
    private final ProfileHistoryRepository profiles=new ProfileHistoryRepository(jdbc,new JsonMapper(),null){
        @Override public String packageOwner(String owner){return "CLOUD";}
        @Override public void requireArchive(String owner){if(!archiveAvailable)throw new IllegalStateException("Archive required");}
        @Override public java.util.Map<String,Object> currentSnapshot(String owner,String name,boolean own){return java.util.Map.of("exists",true,"profile",java.util.Map.of("PROFILE_ID","1"),"attributes",value==null?java.util.Map.of():java.util.Map.of("max_tokens",value));}
        @Override public String beforeEdit(String owner,String name,String id,String attribute,String actor,java.util.Map<String,Object> data){beforeCount++;return "request";}
        @Override public void afterEdit(String owner,String name,String id,String outcome,java.util.Map<String,Object> data){afterCount++;assertThat(outcome).isEqualTo("VERIFIED");}
    };
    private final AiAssistantService service=new AiAssistantService(source,repository,profiles,null);
    private PoolSession session(){var s=new OntologyReadCacheTest().session();s.metadata().assistant().select(selection);return s;}
    @Test void sessionSettingNeverWritesAndCanReturnToInheritance(){try(var s=session()){
        service.tokens(s,"PROFILE","v1",4096,false,false);
        assertThat(s.metadata().assistant().maxTokens()).isEqualTo(4096);assertThat(writes).isZero();
        service.tokens(s,"PROFILE","v1",null,false,false);assertThat(s.metadata().assistant().maxTokens()).isNull();assertThat(writes).isZero();
    }}
    @Test void persistenceRequiresConsentCurrentProfileAndPositiveValue(){try(var s=session()){
        assertThatThrownBy(()->service.tokens(s,"PROFILE","v1",4096,true,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.tokens(s,"PROFILE","v1",null,true,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.tokens(s,"PROFILE","v1",0,false,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.tokens(s,"OTHER","v1",4096,true,true)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->service.tokens(s,"PROFILE","old",4096,true,true)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(writes).isZero();assertThat(source.assistantMaxTokens("PROFILE")).isNull();
    }}
    @Test void confirmedSaveReadsBackAndClearsSessionOverride(){try(var s=session()){
        s.metadata().assistant().maxTokens(1024);service.tokens(s,"PROFILE","v1",8192,true,true);
        assertThat(writes).isEqualTo(1);assertThat(value).isEqualTo("8192");assertThat(s.metadata().assistant().maxTokens()).isNull();
        assertThat(beforeCount).isEqualTo(1);assertThat(afterCount).isEqualTo(1);
    }}
    @Test void missingArchiveBlocksProfileWriteButNotSessionOverride(){try(var s=session()){
        archiveAvailable=false;assertThatThrownBy(()->service.tokens(s,"PROFILE","v1",4096,true,true)).isInstanceOf(IllegalStateException.class);assertThat(writes).isZero();
        service.tokens(s,"PROFILE","v1",4096,false,false);assertThat(s.metadata().assistant().maxTokens()).isEqualTo(4096);
    }}
    @Test void tokenChangesInvalidateConsentAndProfileChangesResetOverride(){try(var s=session()){
        var state=s.metadata().assistant();var now=Instant.now();
        var preview=state.prepare("APP",repository.profile(selection),"F","source",false,"en",now);
        state.maxTokens(4096);assertThatThrownBy(()->state.consume(preview.token(),true,"APP",now)).isInstanceOf(AiAssistant.Failure.class);
        state.select(new AiAssistant.Selection("APP","OTHER"));assertThat(state.maxTokens()).isNull();
    }}
    @Test void inFlightCallsBlockBothSessionAndPersistentChanges(){try(var s=session()){
        var state=s.metadata().assistant();var now=Instant.now();var preview=state.prepare("APP",repository.profile(selection),"F","source",false,"en",now);
        state.consume(preview.token(),true,"APP",now);
        assertThatThrownBy(()->service.tokens(s,"PROFILE","v1",4096,true,true)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->state.maxTokens(4096)).isInstanceOf(AiAssistant.Failure.class);assertThat(writes).isZero();
    }}
    @Test void bindingIsSnapshotProfileScopedAndClearedBetweenSessions(){try(var s=session()){
        var state=s.metadata().assistant();state.maxTokens(4096);source.bind(s.pool(),"APP",state);
        state.maxTokens(8192);assertThat(source.assistantMaxTokens("PROFILE")).isEqualTo(4096);assertThat(source.assistantMaxTokens("OTHER")).isNull();
        source.clear();assertThat(source.assistantMaxTokens("PROFILE")).isNull();
        source.bind(s.pool(),"APP");assertThat(source.assistantMaxTokens("PROFILE")).isNull();source.clear();
    }}
}
