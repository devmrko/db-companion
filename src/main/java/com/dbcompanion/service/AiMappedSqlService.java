package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.AiMappedSql.*;
import com.dbcompanion.repository.AiMappedSqlRepository;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiMappedSqlService {
    private final SessionDataSource source;
    private final AiMappedSqlRepository repository;
    private final TransactionTemplate read;
    public AiMappedSqlService(SessionDataSource source, AiMappedSqlRepository repository) {
        this.source = source; this.repository = repository;
        read = new TransactionTemplate(new DataSourceTransactionManager(source));
        read.setReadOnly(true); read.setTimeout(10);
    }
    public Page page(PoolSession session, Query query) { return query(session, () -> repository.page(query)); }
    public Detail detail(PoolSession session, String id) {
        var key = Key.parse(id);
        return query(session, () -> repository.detail(key));
    }
    private <T> T query(PoolSession session, Supplier<T> work) {
        synchronized (session) {
            // Scope is exactly this login's DB grants, not the selected schema or a different user's pool.
            source.bind(session.pool(), session.metadata().selectedSchema());
            try { return read.execute(status -> work.get()); }
            finally { source.clear(); }
        }
    }
}
