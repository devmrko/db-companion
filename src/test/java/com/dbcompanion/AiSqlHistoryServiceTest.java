package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.AiSqlHistory;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.DatabaseSession;
import com.dbcompanion.repository.AiSqlHistoryRepository;
import com.dbcompanion.service.AiSqlHistoryService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class AiSqlHistoryServiceTest {
    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getAutoCommit" -> true;
            case "isClosed", "isReadOnly" -> false;
            case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
            case "toString" -> "test-only connection";
            default -> {
                if (method.getReturnType() == boolean.class) yield false;
                if (method.getReturnType() == int.class) yield 0;
                yield null;
            }
        });
    }

    private final SessionDataSource source = new SessionDataSource() {
        @Override public Connection getConnection() { return connection(); }
    };

    private PoolSession session() {
        var session = new PoolSession(new HikariDataSource(), "LOW", () -> {});
        session.initialize(new DatabaseSession(new DatabaseInfo("APP", "APP", "LOW", "DB"), List.of("APP")));
        return session;
    }

    private static AiSqlHistory.Query cacheQuery() {
        return new AiSqlHistory.Query(AiSqlHistory.Source.cache, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 23),
                "abc123def4567", "inventory", "APP", 2, AiSqlHistory.Match.generate);
    }

    @Test void cacheEmptyPageRemainsSuccessfulAndPreservesAllFilters() {
        var repository = new StubRepository();
        var query = cacheQuery();
        repository.result = new AiSqlHistory.Page(List.of(), query.page(), false, Instant.EPOCH, null);
        var service = new AiSqlHistoryService(source, repository);

        try (var session = session()) {
            var page = service.page(session, query, false);

            assertThat(page.items()).isEmpty();
            assertThat(page.failure()).isNull();
            assertThat(repository.calls).isEqualTo(1);
            assertThat(repository.query).isEqualTo(query);
            assertThat(repository.query.sqlId()).isEqualTo("abc123def4567");
            assertThat(repository.query.text()).isEqualTo("inventory");
            assertThat(repository.query.actor()).isEqualTo("APP");
            assertThat(repository.query.page()).isEqualTo(2);
            assertThat(repository.query.match()).isEqualTo(AiSqlHistory.Match.generate);
        }
    }

    @Test void cacheCancellationCodeIsPreserved() {
        assertCacheFailureCode(1013);
    }

    @Test void cachePermissionCodeIsPreserved() {
        assertCacheFailureCode(1031);
    }

    @Test void awrWithoutAcknowledgementDoesNotCallRepository() {
        var repository = new StubRepository();
        var service = new AiSqlHistoryService(source, repository);
        var query = new AiSqlHistory.Query(AiSqlHistory.Source.awr, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 23),
                "abc123def4567", "inventory", "", 1);

        try (var session = session()) {
            assertThatThrownBy(() -> service.page(session, query, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("AWR use must be acknowledged");
            assertThat(repository.calls).isZero();
        }
    }
    @Test void candidateLookupRequiresAnOpaqueSelectionOwnedByThisLoginSession() {
        var repository=new StubRepository();var service=new AiSqlHistoryService(source,repository);
        try(var first=session();var second=session()) {
            String id=java.util.UUID.randomUUID().toString();
            var item=new AiSqlHistory.Item(id,"time","abc123def4567","APP","GENERATE","1","call",List.of());
            repository.result=new AiSqlHistory.Page(List.of(item),1,false,Instant.EPOCH,null);
            service.page(first,cacheQuery(),false);
            assertThat(service.candidates(second,id)).isNull();assertThat(repository.candidateCalls).isZero();
            assertThatThrownBy(()->service.candidates(first,"abc123def4567")).isInstanceOf(IllegalArgumentException.class);
            assertThat(repository.candidateCalls).isZero();
            assertThat(service.candidates(first,id).items()).isEmpty();assertThat(repository.candidateCalls).isEqualTo(1);
            first.metadata().sqlHistory().refresh(AiSqlHistory.Source.cache);
            assertThat(service.candidates(first,id)).isNull();assertThat(repository.candidateCalls).isEqualTo(1);
        }
    }

    private void assertCacheFailureCode(int code) {
        var repository = new StubRepository();
        repository.failure = new DataAccessResourceFailureException("read failed", new SQLException("ORA-" + String.format("%05d", code), "72000", code));
        var service = new AiSqlHistoryService(source, repository);

        try (var session = session()) {
            var page = service.page(session, cacheQuery(), false);
            assertThat(page.items()).isEmpty();
            assertThat(page.failure().code()).isEqualTo(code);
            assertThat(page.failure().details()).contains("SYS.V_$SQL", "Oracle code=" + code);
        }
    }

    private static final class StubRepository extends AiSqlHistoryRepository {
        private int calls;
        private int candidateCalls;
        private AiSqlHistory.Query query;
        private AiSqlHistory.Page result;
        private RuntimeException failure;

        private StubRepository() { super(new JdbcTemplate(new SessionDataSource())); }
        @Override public AiSqlHistory.Candidates candidates(AiSqlHistory.Selection selected) {
            candidateCalls++;
            return new AiSqlHistory.Candidates("time","APP","NOT_CHECKED",List.of(),false,0);
        }

        @Override public AiSqlHistory.Page page(AiSqlHistory.Query query) {
            calls++;
            this.query = query;
            if (failure != null) throw failure;
            return result;
        }
    }
}
