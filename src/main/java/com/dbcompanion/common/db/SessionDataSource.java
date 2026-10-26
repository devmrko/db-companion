package com.dbcompanion.common.db;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.stereotype.Component;

@Component
public class SessionDataSource extends AbstractDataSource {
    private record Binding(DataSource source, String schema,String aiProfile,Integer maxTokens) {}
    private final ThreadLocal<Binding> current = new ThreadLocal<>();

    public void bind(DataSource source, String schema) {
        if (current.get() != null) throw new IllegalStateException("Nested pool binding");
        current.set(new Binding(source, schema,null,null));
    }

    /** Snapshot the login's override; it applies only to the selected assistant profile. */
    public void bind(DataSource source,String schema,com.dbcompanion.model.AiAssistant.State assistant){
        bind(source,schema);
        synchronized(assistant){
            var selected=assistant.selected();
            current.set(new Binding(source,schema,selected==null?null:selected.name(),assistant.maxTokens()));
        }
    }
    public Integer assistantMaxTokens(String profile){
        var binding=current.get();
        return binding!=null&&java.util.Objects.equals(binding.aiProfile(),profile)?binding.maxTokens():null;
    }

    public void clear() { current.remove(); }

    @Override
    public Connection getConnection() throws SQLException {
        var source = current.get();
        if (source == null) throw new SQLException("No authenticated database session");
        var connection = source.source().getConnection();
        try {
            // A newly created or reused physical connection must have the same default schema.
            // Apply before the service transaction; ALTER SESSION is not undone by rollback.
            if (source.schema() != null) {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(10);
                    statement.execute(schemaSql(source.schema()));
                }
            }
            return connection;
        } catch (SQLException | RuntimeException ex) {
            try { connection.close(); }
            catch (SQLException closeError) { ex.addSuppressed(closeError); }
            throw ex;
        }
    }

    public static String schemaSql(String schema) {
        if (schema == null || schema.isEmpty() || schema.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid schema identifier");
        return "ALTER SESSION SET CURRENT_SCHEMA = \"" + schema.replace("\"", "\"\"") + "\"";
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("Credentials must be supplied at login");
    }
}
