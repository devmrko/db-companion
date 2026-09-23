package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.AiFeedback;
import com.dbcompanion.model.AiFeedback.*;
import com.dbcompanion.repository.AiFeedbackRepository;
import com.dbcompanion.repository.DatabaseRepository;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import static com.dbcompanion.common.exception.AppException.Code.*;

@Service
public class AiFeedbackService {
    private final SessionDataSource source;
    private final AiFeedbackRepository repository;
    private final DatabaseRepository catalog;
    private final TransactionTemplate read;
    public AiFeedbackService(SessionDataSource source, AiFeedbackRepository repository, DatabaseRepository catalog) {
        this.source = source; this.repository = repository; this.catalog = catalog;
        read = new TransactionTemplate(new DataSourceTransactionManager(source));
        read.setReadOnly(true); read.setTimeout(10);
    }
    public static boolean ownScope(String username, String selectedSchema, Query query) {
        if (!query.schema().isEmpty() && !selectedSchema.equals(query.schema()))
            throw new IllegalArgumentException("Selected schema changed");
        boolean own = username.equals(selectedSchema);
        if (!own && !"ADMIN".equals(username)) throw new AppException(PROFILE_SCHEMA_RESTRICTED);
        return own;
    }
    public Result load(PoolSession session, Query query, String id) {
        synchronized (session) {
            var metadata = session.metadata();
            boolean own = ownScope(metadata.info().username(), metadata.selectedSchema(), query);
            if (id != null) {
                AiFeedback.rowId(id);
                if (query.profile().isEmpty()) throw new IllegalArgumentException("Profile required");
            }
            source.bind(session.pool(), metadata.selectedSchema());
            try {
                return read.execute(status -> {
                    var profiles = catalog.profiles(metadata.selectedSchema(), own, id == null ? null : query.profile());
                    if (query.profile().isEmpty()) return new Result(profiles, false, null, null);
                    if (profiles.stream().noneMatch(p -> p.name().equals(query.profile()))) throw new AppException(PROFILE_NOT_ACCESSIBLE);
                    if (!repository.tableExists(query.schema(), query.profile())) return new Result(profiles, true, null, null);
                    return id == null ? new Result(profiles, false, repository.page(query), null)
                            : new Result(profiles, false, null, repository.detail(query, id));
                });
            } finally { source.clear(); }
        }
    }
}
