package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.OntologyService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyReadCacheTest {
    int connections,tableLoads,catalogLoads,networkTimeout=15_000;
    boolean failTables;
    final SessionDataSource source=new SessionDataSource(){
        @Override public Connection getConnection(){connections++;return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
            case "getAutoCommit" -> true;
            case "getNetworkTimeout" -> networkTimeout;
            case "setNetworkTimeout" -> {networkTimeout=(int)a[1];yield null;}
            case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
            case "equals" -> p==a[0];case "hashCode" -> System.identityHashCode(p);
            default -> m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
        });}
    };
    final JdbcTemplate jdbc=new JdbcTemplate(source);
    final JsonMapper json=new JsonMapper();
    final OntologyRepository repository=new OntologyRepository(jdbc,json,new DatabaseRepository(jdbc),new TableStructureRepository(jdbc)){
        @Override public Catalog catalog(String schema,String login,Supplier<List<TableInfo>> tables){
            catalogLoads++;
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);
            assertThat(holder.getTimeToLiveInSeconds()).isBetween(25,30);
            assertThat(networkTimeout).isEqualTo(40_000);
            return new Catalog("READY",false,tables.get(),List.of(),"read-"+catalogLoads);
        }
        @Override public List<TableInfo> tables(String schema){tableLoads++;if(failTables)throw new IllegalStateException("test read failure");return List.of(new TableInfo(schema+"_T","comment-"+tableLoads));}
    };
    final OntologyService service=new OntologyService(source,repository,new AiAssistantRepository(jdbc),null,json);
    PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));return s;}

    @Test void repeatAndReentryUseSessionCacheWithoutOpeningConnection(){try(var s=session()){
        var first=service.catalog(s,"APP",false);
        assertThat(service.catalog(s,"APP",false)).isSameAs(first);
        s.metadata().selectSchema("OTHER");s.metadata().selectSchema("APP");
        assertThat(service.catalog(s,"APP",false)).isSameAs(first);
        assertThat(connections).isEqualTo(1);assertThat(tableLoads).isEqualTo(1);
        assertThat(networkTimeout).isEqualTo(15_000);
    }}
    @Test void savedRevisionInvalidationRetainsCommentsUntilExplicitRefresh(){try(var s=session()){
        var first=service.catalog(s,"APP",false);
        s.metadata().ontology().clear("APP");
        var saved=service.catalog(s,"APP",false);
        assertThat(saved.checkedAt()).isNotEqualTo(first.checkedAt());
        assertThat(saved.tables()).isEqualTo(first.tables());assertThat(tableLoads).isEqualTo(1);
        s.metadata().ontology().clearReview("APP");service.catalog(s,"APP",false);
        assertThat(tableLoads).isEqualTo(1);
        assertThat(service.catalog(s,"APP",true).tables()).isNotEqualTo(first.tables());
        assertThat(tableLoads).isEqualTo(2);
    }}
    @Test void freshLoginAndDifferentSchemaNeverReuseAnotherCache(){try(var a=session();var b=session()){
        service.catalog(a,"APP",false);service.catalog(b,"APP",false);
        a.metadata().selectSchema("OTHER");var other=service.catalog(a,"OTHER",false);
        assertThat(other.tables().getFirst().name()).isEqualTo("OTHER_T");
        assertThat(tableLoads).isEqualTo(3);
    }}
    @Test void refreshFailureDiscardsOldMetadataAndFailedReadsAreNotCached(){try(var s=session()){
        service.catalog(s,"APP",false);failTables=true;
        assertThatThrownBy(()->service.catalog(s,"APP",true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.catalog(s,"APP",false)).isInstanceOf(IllegalStateException.class);
        assertThat(tableLoads).isEqualTo(3);assertThat(networkTimeout).isEqualTo(15_000);
        failTables=false;var next=service.catalog(s,"APP",false);
        assertThat(next.tables().getFirst().description()).isEqualTo("comment-4");
    }}
    @Test void metadataCacheCannotBeChangedByCaller(){var s=new State();var list=new ArrayList<>(List.of(new TableInfo("T","comment")));var stored=s.tables("APP",()->list);list.clear();assertThat(stored).hasSize(1);assertThatThrownBy(stored::clear).isInstanceOf(UnsupportedOperationException.class);}
}
