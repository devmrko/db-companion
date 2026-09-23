package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.AiAgentExecution;
import com.dbcompanion.model.AiAgentExecution.*;
import com.dbcompanion.repository.AiAgentExecutionRepository;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiAgentExecutionService {
    private final SessionDataSource source;
    private final AiAgentExecutionRepository repository;
    private final TransactionTemplate read;
    public AiAgentExecutionService(SessionDataSource source, AiAgentExecutionRepository repository) {
        this.source = source; this.repository = repository;
        read = new TransactionTemplate(new DataSourceTransactionManager(source));
        read.setReadOnly(true); read.setTimeout(10);
    }
    public Page<Run> page(PoolSession session, String schema, Query query) {
        return query(session, schema, () -> repository.page(query));
    }
    public RunDetail run(PoolSession session, String schema, String id, int page) {
        AiAgentExecution.requireRunId(id); AiAgentExecution.requirePage(page);
        return query(session, schema, () -> {
            var run = repository.run(id);
            return run == null ? null : new RunDetail(run, repository.tasks(id, page));
        });
    }
    public TaskDetail task(PoolSession session, String schema, String id, long order) {
        AiAgentExecution.requireRunId(id); AiAgentExecution.requireOrder(order);
        return query(session, schema, () -> repository.task(id, order));
    }
    public Conversations conversations(PoolSession session, String schema, String id, long order, int page) {
        AiAgentExecution.requireRunId(id); AiAgentExecution.requireOrder(order); AiAgentExecution.requirePage(page);
        return query(session, schema, () -> repository.conversations(id, order, page));
    }
    private <T> T query(PoolSession session, String schema, Supplier<T> work) {
        synchronized (session) {
            var metadata = session.metadata();
            AiExecutionHistoryService.requireScope(metadata.info().username(), metadata.selectedSchema(), schema);
            source.bind(session.pool(), metadata.selectedSchema());
            try { return read.execute(status -> work.get()); }
            finally { source.clear(); }
        }
    }
}
