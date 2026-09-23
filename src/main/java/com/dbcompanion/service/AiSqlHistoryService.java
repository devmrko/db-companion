package com.dbcompanion.service;

import com.dbcompanion.common.db.OracleErrorDetails;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.AiSqlHistory.*;
import com.dbcompanion.repository.AiSqlHistoryRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiSqlHistoryService {
    private final SessionDataSource source;
    private final AiSqlHistoryRepository repository;
    private final TransactionTemplate read;
    public AiSqlHistoryService(SessionDataSource source, AiSqlHistoryRepository repository) {
        this.source = source; this.repository = repository;
        read = new TransactionTemplate(new DataSourceTransactionManager(source));
        read.setReadOnly(true); read.setTimeout(10);
    }
    public Page page(PoolSession session, Query query, boolean awrAllowed) {
        if (query.source() == Source.awr && !awrAllowed) throw new IllegalArgumentException("AWR use must be acknowledged");
        synchronized (session) {
            return session.metadata().sqlHistory().page(query, () -> {
                try { return query(session, () -> repository.page(query)); }
                catch (RuntimeException ex) {
                    String stage = switch (query.source()) {
                        case cache -> "SYS.V_$SQL";
                        case awr -> "SYS.DBA_HIST_SQLTEXT / SYS.DBA_HIST_SQLSTAT / SYS.DBA_HIST_SNAPSHOT";
                        case audit -> "UNIFIED_AUDIT_TRAIL";
                    };
                    return new Page(List.of(), query.page(), false, Instant.now(), failure(ex, stage));
                }
            });
        }
    }
    public Policies policies(PoolSession session) {
        synchronized (session) {
            return session.metadata().sqlHistory().policies(() -> {
                try { return query(session, repository::policies); }
                catch (RuntimeException ex) { return new Policies(List.of(), false, Instant.now(), failure(ex, "SYS.AUDIT_UNIFIED_POLICIES / SYS.AUDIT_UNIFIED_ENABLED_POLICIES")); }
            });
        }
    }
    public Detail detail(PoolSession session, String id) {
        synchronized (session) {
            var selected = session.metadata().sqlHistory().selection(id);
            return selected == null ? null : query(session, () -> repository.detail(selected));
        }
    }
    private <T> T query(PoolSession session, Supplier<T> work) {
        source.bind(session.pool(), session.metadata().info().username());
        try { return read.execute(status -> work.get()); }
        finally { source.clear(); }
    }
    public static Failure failure(Throwable ex, String stage) {
        Throwable current = ex;
        while (!(current instanceof SQLException) && current.getCause() != null) current = current.getCause();
        int code = current instanceof SQLException sql ? sql.getErrorCode() : 0;
        String raw = OracleErrorDetails.forDisplay(ex);
        return new Failure(code, stage + " · Oracle code=" + code + " · " + (raw.isBlank() ?
                (current instanceof SourceUnavailable ? current.getMessage() : current.getClass().getSimpleName()) : raw));
    }
}
