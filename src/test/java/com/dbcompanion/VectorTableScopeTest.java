package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.DatabaseSession;
import com.dbcompanion.model.VectorSearch.Table;
import com.dbcompanion.repository.VectorSearchRepository;
import com.dbcompanion.service.VectorSearchService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class VectorTableScopeTest {
    private final List<List<String>> calls=new ArrayList<>();
    private final SessionDataSource source=new SessionDataSource() {
        @Override public Connection getConnection() {
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->switch(m.getName()) {
                case "getAutoCommit" -> true;
                case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                default -> m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
            });
        }
    };
    private final VectorSearchRepository repository=new VectorSearchRepository(new JdbcTemplate(source)) {
        @Override public List<Table> tables(String schema,String login) {
            calls.add(List.of(schema,login));return List.of(new Table(schema+"_DOCS",null,List.of("EMBED")));
        }
    };
    private final VectorSearchService service=new VectorSearchService(source,repository,null,new JsonMapper(),null);
    private PoolSession session() {
        var session=new PoolSession(new HikariDataSource(),"LOW",()->{});
        session.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));
        return session;
    }
    @Test void schemaSwitchPassesAuthenticatedLoginAndKeepsCatalogsSeparate() {
        try(var session=session()) {
            assertThat(service.tables(session,false).getFirst().name()).isEqualTo("APP_DOCS");
            session.metadata().selectSchema("OTHER");
            assertThat(service.tables(session,false).getFirst().name()).isEqualTo("OTHER_DOCS");
            assertThat(calls).containsExactly(List.of("APP","APP"),List.of("OTHER","APP"));
        }
    }
    @Test void cachedReadAndExplicitRefreshKeepLoginScope() {
        try(var session=session()) {
            service.tables(session,false);service.tables(session,false);
            assertThat(calls).hasSize(1);
            service.tables(session,true);
            assertThat(calls).containsExactly(List.of("APP","APP"),List.of("APP","APP"));
        }
    }
}
