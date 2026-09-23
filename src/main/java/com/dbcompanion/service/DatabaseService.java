package com.dbcompanion.service;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.exception.AppException;
import static com.dbcompanion.common.exception.AppException.Code.*;

import com.dbcompanion.common.db.OraclePoolFactory;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.DatabaseSession;
import com.dbcompanion.model.TableInfo;
import com.dbcompanion.model.ColumnInfo;
import com.dbcompanion.model.AnnotationInfo;
import org.springframework.dao.DataAccessException;
import com.dbcompanion.repository.DatabaseRepository;
import com.dbcompanion.repository.TableStructureRepository;
import com.dbcompanion.model.TableStructure;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DatabaseService {
    private final OraclePoolFactory factory;
    private final SessionDataSource source;
    private final DatabaseRepository repository;
    private final TableStructureRepository structure;
    private final com.dbcompanion.repository.RoutineSourceRepository routines;
    private final TransactionTemplate readTransaction;

    public DatabaseService(OraclePoolFactory factory, SessionDataSource source, DatabaseRepository repository,
                           TableStructureRepository structure, com.dbcompanion.repository.RoutineSourceRepository routines) {
        this.factory = factory;
        this.source = source;
        this.repository = repository;
        this.structure = structure;
        this.routines = routines;
        readTransaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        readTransaction.setReadOnly(true);
        readTransaction.setTimeout(10);
    }

    public LoginResult login(String username, String password, String alias) {
        return login(username, password, alias, "");
    }

    public LoginResult login(String username, String password, String alias, String walletId) {
        if (username == null || username.isBlank() || username.length() > 128
                || password == null || password.isEmpty() || password.length() > 1024) {
            throw new AppException(CREDENTIALS_REQUIRED);
        }
        var session = factory.open(username, password, alias, walletId);
        try {
            var metadata = inReadTransaction(session, () ->
                    new DatabaseSession(repository.info(), repository.accessibleSchemas()));
            session.initialize(metadata);
            return new LoginResult(session, metadata.info());
        } catch (RuntimeException ex) {
            session.close();
            throw ex;
        }
    }

    public DatabaseInfo info(PoolSession session) {
        return session.metadata().info();
    }

    public Dashboard dashboard(PoolSession session) {
        synchronized (session) {
            var metadata = session.metadata();
            return new Dashboard(metadata.info(), metadata.schemas(), metadata.selectedSchema());
        }
    }

    public TablePage tables(PoolSession session) {
        return inReadTransaction(session, () -> {
            var dashboard = dashboard(session);
            return new TablePage(dashboard, repository.tables(dashboard.selectedSchema()));
        });
    }

    public void selectSchema(PoolSession session, String schema) {
        synchronized (session) {
            var metadata = session.metadata();
            if (schema == null || !metadata.schemas().contains(schema)) throw new AppException(SCHEMA_NOT_ACCESSIBLE);
            var actual = inReadTransaction(session, schema, repository::info);
            if (!schema.equals(actual.schema()) || !metadata.info().username().equals(actual.username()))
                throw new IllegalStateException("Database schema selection was not applied");
            metadata.selectSchema(schema);
        }
    }

    public ProfilePage profiles(PoolSession session, String name) {
        synchronized (session) {
            var dashboard = dashboard(session);
            boolean own = dashboard.info().username().equals(dashboard.selectedSchema());
            if (!own && !"ADMIN".equals(dashboard.info().username())) throw new AppException(PROFILE_SCHEMA_RESTRICTED);
            return inReadTransaction(session, () -> {
                var profiles = repository.profiles(dashboard.selectedSchema(), own, name);
                if (name != null && profiles.isEmpty()) throw new AppException(PROFILE_NOT_ACCESSIBLE);
                var attributes = name == null ? List.<com.dbcompanion.model.AiProfileAttribute>of()
                        : repository.profileAttributes(dashboard.selectedSchema(), own, name);
                return new ProfilePage(profiles, attributes);
            });
        }
    }

    public record ProfilePage(List<com.dbcompanion.model.AiProfile> profiles,
                              List<com.dbcompanion.model.AiProfileAttribute> attributes) {}

    public com.dbcompanion.model.RoutineSource routineSource(PoolSession session, String schema, String name) {
        synchronized (session) {
            if (!schema.equals(session.metadata().selectedSchema())) throw new AppException(AGENT_SCHEMA_CHANGED);
            return inReadTransaction(session, () -> routines.source(schema, name));
        }
    }

    public ProfileObjects profileObjects(PoolSession session, String schema, String profile) {
        synchronized (session) {
            if (!schema.equals(session.metadata().selectedSchema())) throw new IllegalArgumentException("Schema changed");
            boolean own = schema.equals(session.metadata().info().username());
            if (!own && !"ADMIN".equals(session.metadata().info().username())) throw new AppException(PROFILE_SCHEMA_RESTRICTED);
            return inReadTransaction(session, () -> {
                if (repository.profiles(schema, own, profile).isEmpty()) throw new AppException(PROFILE_NOT_ACCESSIBLE);
                return new ProfileObjects(repository.profileObjectList(schema, own, profile));
            });
        }
    }

    public record ProfileObjects(String objectList) {}

    public void refreshSchemas(PoolSession session) {
        synchronized (session) {
            var schemas = inReadTransaction(session, repository::accessibleSchemas);
            session.metadata().refreshSchemas(schemas);
        }
    }

    public TableDetail detail(PoolSession session, String schema, String table, String tab) {
        return inReadTransaction(session, () -> {
            var target = repository.table(schema, table);
            if (target == null) throw new AppException(TABLE_NOT_ACCESSIBLE);
            var metadata = session.metadata();
            var dashboard = new Dashboard(metadata.info(), metadata.schemas(), metadata.selectedSchema());
            var columns = java.util.Set.of("columns", "comments").contains(tab)
                    ? repository.columns(schema, table) : List.<ColumnInfo>of();
            List<AnnotationInfo> annotations = List.of();
            String annotationError = null;
            if ("annotations".equals(tab)) {
                try {
                    annotations = repository.annotations(schema, table);
                } catch (DataAccessException ex) {
                    Throwable cause = ex.getMostSpecificCause();
                    int code = cause instanceof java.sql.SQLException sql ? sql.getErrorCode() : 0;
                    org.slf4j.LoggerFactory.getLogger(DatabaseService.class).warn("Annotation query error: code={}", code);
                    annotationError = UiMessages.text("ui.2b29491ba150", "Annotation을 조회하지 못했습니다. DB 지원 버전과 조회 권한을 확인해 주세요.");
                }
            }
            var constraints = "constraints".equals(tab) ? structure.constraints(schema, table) : List.<TableStructure.Constraint>of();
            var indexes = "indexes".equals(tab) ? structure.indexes(schema, table) : List.<TableStructure.Index>of();
            return new TableDetail(dashboard, target, columns, annotations, annotationError, constraints, indexes);
        });
    }

    private <T> T inReadTransaction(PoolSession session, Supplier<T> work) {
        synchronized (session) {
            return inReadTransaction(session, session.connectionSchema(), work);
        }
    }

    private <T> T inReadTransaction(PoolSession session, String schema, Supplier<T> work) {
        synchronized (session) {
            source.bind(session.pool(), schema);
            try {
                // Bind the user's pool BEFORE starting the service transaction.
                return readTransaction.execute(status -> work.get());
            } finally {
                source.clear();
            }
        }
    }

    public record LoginResult(PoolSession session, DatabaseInfo info) {}
    public record Dashboard(DatabaseInfo info, List<String> schemas, String selectedSchema) {}
    public record TablePage(Dashboard dashboard, List<TableInfo> tables) {}
    public record TableDetail(Dashboard dashboard, TableInfo table, List<ColumnInfo> columns,
                              List<AnnotationInfo> annotations, String annotationError,
                              List<TableStructure.Constraint> constraints, List<TableStructure.Index> indexes) {}
}
