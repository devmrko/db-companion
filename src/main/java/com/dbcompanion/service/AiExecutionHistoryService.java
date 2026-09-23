package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.AiExecutionHistory;
import com.dbcompanion.model.AiExecutionHistory.*;
import com.dbcompanion.repository.AiExecutionHistoryRepository;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiExecutionHistoryService {
    private final SessionDataSource source;
    private final AiExecutionHistoryRepository repository;
    private final TransactionTemplate read;
    public AiExecutionHistoryService(SessionDataSource source, AiExecutionHistoryRepository repository) {
        this.source = source; this.repository = repository;
        read = new TransactionTemplate(new DataSourceTransactionManager(source));
        read.setReadOnly(true); read.setTimeout(10);
    }
    public Page page(PoolSession session, String schema, Query query) {
        return query(session, schema, () -> repository.page(query));
    }
    public Detail detail(PoolSession session, String schema, String id) {
        AiExecutionHistory.requireId(id);
        return query(session, schema, () -> repository.detail(id));
    }
    public static void requireScope(String username, String selectedSchema, String requestedSchema) {
        if (!selectedSchema.equals(requestedSchema)) throw new AppException(AppException.Code.EXECUTION_SCHEMA_CHANGED);
        if (!username.equals(selectedSchema)) throw new AppException(AppException.Code.EXECUTION_OWN_SCHEMA_ONLY);
    }
    private <T> T query(PoolSession session, String schema, Supplier<T> work) {
        synchronized (session) {
            var metadata = session.metadata();
            requireScope(metadata.info().username(), metadata.selectedSchema(), schema);
            source.bind(session.pool(), metadata.selectedSchema());
            try { return read.execute(status -> work.get()); }
            finally { source.clear(); }
        }
    }
}
