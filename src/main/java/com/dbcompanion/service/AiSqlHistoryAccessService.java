package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.repository.AiSqlHistoryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Zero-row capability checks using the login's pool. Never grants access or reads history. */
@Service
public class AiSqlHistoryAccessService {
    public record Access(boolean available, int code, String state) {
        public static Access allowed() { return new Access(true, 0, "available"); }
        public static Access denied(int code) {
            // ORA-00942 cannot distinguish an absent view from an invisible one.
            return new Access(false, code, code == 1031 ? "required" : code == 942 ? "check" : "unknown");
        }
    }
    private final SessionDataSource source;
    private final JdbcTemplate jdbc;
    private final AiSqlHistoryRepository repository;
    private final TransactionTemplate read;
    public AiSqlHistoryAccessService(SessionDataSource source, AiSqlHistoryRepository repository) {
        this.source = source; this.repository = repository;
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(3);
        read = new TransactionTemplate(new DataSourceTransactionManager(source));
        read.setReadOnly(true); read.setTimeout(3);
    }
    public static List<String> probes(String name, String auditView) {
        return switch (name) {
            case "mapping" -> List.of("SELECT SQL_ID, MAPPED_SQL_ID, SQL_TEXT, TRANSLATION_TIMESTAMP FROM SYS.V_$MAPPED_SQL WHERE 1=0");
            case "cache" -> List.of("SELECT SQL_ID, SQL_FULLTEXT, EXECUTIONS, LAST_ACTIVE_TIME FROM SYS.V_$SQL WHERE 1=0");
            case "awr" -> List.of("SELECT SQL_ID, SQL_TEXT FROM SYS.DBA_HIST_SQLTEXT WHERE 1=0",
                    "SELECT SQL_ID, EXECUTIONS_DELTA FROM SYS.DBA_HIST_SQLSTAT WHERE 1=0",
                    "SELECT SNAP_ID, END_INTERVAL_TIME FROM SYS.DBA_HIST_SNAPSHOT WHERE 1=0");
            case "audit" -> {
                if (!List.of("SYS.UNIFIED_AUDIT_TRAIL", "AUDSYS.UNIFIED_AUDIT_TRAIL").contains(auditView)) throw new IllegalArgumentException("Invalid audit view");
                yield List.of("SELECT EVENT_TIMESTAMP_UTC, SQL_TEXT, DBUSERNAME FROM " + auditView + " WHERE 1=0");
            }
            case "policies" -> List.of("SELECT POLICY_NAME FROM SYS.AUDIT_UNIFIED_POLICIES WHERE 1=0",
                    "SELECT POLICY_NAME FROM SYS.AUDIT_UNIFIED_ENABLED_POLICIES WHERE 1=0");
            default -> throw new IllegalArgumentException("Unknown SQL history source");
        };
    }
    public Map<String, Access> inspect(PoolSession session) {
        synchronized (session) {
            source.bind(session.pool(), session.metadata().info().username());
            try {
                var result = new LinkedHashMap<String, Access>();
                for (String name : List.of("mapping", "cache", "awr", "audit", "policies")) {
                    try {
                        read.execute(status -> {
                            for (String sql : probes(name, name.equals("audit") ? repository.auditView() : "")) jdbc.queryForList(sql);
                            return null;
                        });
                        result.put(name, Access.allowed());
                    } catch (RuntimeException ex) {
                        result.put(name, Access.denied(AiSqlHistoryService.failure(ex, name).code()));
                    }
                }
                return Map.copyOf(result);
            } finally { source.clear(); }
        }
    }
}
