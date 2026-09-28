package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.BusinessGlossary.*;
import com.dbcompanion.model.BusinessGlossaryHistory.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.BusinessGlossaryService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class BusinessGlossaryHistoryTest {
    final String id="11111111-1111-1111-1111-111111111111";
    Term term=new Term(id,1,"Calendar window",List.of("previous period"),"Original definition","old rule",true,"before");
    int commits,rollbacks,saves,installs;boolean failHistory,ready=true;
    final List<Change> recorded=new ArrayList<>();
    final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
        case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;
        case "commit"->{commits++;yield null;}case "rollback"->{rollbacks++;yield null;}
        default->m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
    });}};
    final JsonMapper json=new JsonMapper();
    final BusinessGlossaryRepository terms=new BusinessGlossaryRepository(new JdbcTemplate(source),json){
        @Override public void requireTable(String owner){assertThat(owner).isEqualTo("APP");}
        @Override public String tableStatus(String owner){return "READY";}
        @Override public String textStatus(String owner){return "READY";}
        @Override public Term find(String owner,String key){assertThat(owner).isEqualTo("APP");assertThat(key).isEqualTo(id);return term;}
        @Override public Term save(String owner,String key,long revision,Draft d){
            saves++;assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return new Term(id,revision+1,d.term(),d.aliases(),d.definition(),d.criteria(),d.enabled(),"after");
        }
    };
    final BusinessGlossaryHistoryRepository history=new BusinessGlossaryHistoryRepository(new JdbcTemplate(source),null,json){
        @Override public String status(String owner){assertThat(owner).isEqualTo("APP");return ready?"READY":"MISSING";}
        @Override public void install(String owner){installs++;ready=true;}
        @Override public void append(String owner,Term before,Term after){
            assertThat(owner).isEqualTo("APP");assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            if(failHistory)throw new IllegalStateException("History write failed");
            recorded.add(new Change(1,after.id(),before==null?"CREATE":"UPDATE",before,after));
        }
    };
    final BusinessGlossaryService service=new BusinessGlossaryService(source,terms,json,history);
    PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));s.metadata().selectSchema("OTHER");return s;}
    @Test void createStoresOnlyTheRealInitialVersionWithNoInventedBefore(){try(var s=session()){
        var saved=service.save(s,null,0,term.draft(),true);
        assertThat(saved.revision()).isEqualTo(1);assertThat(recorded).hasSize(1);
        assertThat(recorded.getFirst().before()).isNull();assertThat(recorded.getFirst().after()).isEqualTo(saved);
        assertThat(recorded.getFirst().operation()).isEqualTo("CREATE");assertThat(commits).isEqualTo(1);
    }}
    @Test void updateRetainsTheCompleteBeforeAndAfterSnapshot(){try(var s=session()){
        var draft=new Draft("Revised window",List.of("one","two"),"New definition","new rule",false);
        var saved=service.save(s,id,1,draft,true);
        assertThat(recorded.getFirst().before()).isEqualTo(term);assertThat(recorded.getFirst().after()).isEqualTo(saved);
        assertThat(saved.draft()).isEqualTo(draft);assertThat(saved.revision()).isEqualTo(2);
        assertThat(json.readValue(json.writeValueAsString(recorded.getFirst()),Change.class)).isEqualTo(recorded.getFirst());
    }}
    @Test void historyFailureRollsBackTheTermTransactionWithoutRetry(){try(var s=session()){
        failHistory=true;assertThatThrownBy(()->service.save(s,id,1,term.draft(),true)).hasMessage("History write failed");
        assertThat(saves).isEqualTo(1);assertThat(commits).isZero();assertThat(rollbacks).isEqualTo(1);assertThat(recorded).isEmpty();
    }}
    @Test void staleVersionAndMissingHistoryCannotSaveATerm(){try(var s=session()){
        assertThatThrownBy(()->service.save(s,id,2,term.draft(),true)).isInstanceOf(AiAssistant.Failure.class);
        ready=false;assertThatThrownBy(()->service.save(s,id,1,term.draft(),true)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(saves).isZero();assertThat(recorded).isEmpty();assertThat(installs).isZero();
    }}
    @Test void historySetupIsExplicitPreviewConsentAndOneUse(){try(var s=session()){
        ready=false;var setup=service.setupPreview(s,"HISTORY");assertThat(installs).isZero();
        assertThat(setup.statements()).hasSize(1);assertThat(setup.statements().getFirst()).contains("DBC_APP_RECORD").doesNotContain("TRIGGER");
        assertThatThrownBy(()->service.setup(s,setup.token(),false)).isInstanceOf(AiAssistant.Failure.class);
        service.setup(s,setup.token(),true);assertThat(installs).isEqualTo(1);
        assertThatThrownBy(()->service.setup(s,setup.token(),true)).isInstanceOf(AiAssistant.Failure.class);
    }}
    @Test void malformedSnapshotCannotPretendToBeAPastVersion(){
        assertThatThrownBy(()->new Change(1,id,"UPDATE",null,term)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->new Change(1,id,"UPDATE",term,term)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->new Change(2,id,"CREATE",null,term)).isInstanceOf(AiAssistant.Failure.class);
    }
}
