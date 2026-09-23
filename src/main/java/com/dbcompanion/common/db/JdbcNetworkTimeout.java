package com.dbcompanion.common.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Temporarily raises a connection's socket timeout inside an existing Spring transaction. */
public final class JdbcNetworkTimeout {
    private JdbcNetworkTimeout() {}

    public static <T> T execute(DataSource source,int minimumMillis,Supplier<T> action) {
        if(minimumMillis<=0||!TransactionSynchronizationManager.hasResource(source))
            throw new IllegalStateException("Network timeout scope requires a bound transaction");
        Connection connection=DataSourceUtils.getConnection(source);
        try(Scope ignored=new Scope(connection,minimumMillis)) {
            return action.get();
        } catch(SQLException ex) {
            throw new DataAccessResourceFailureException("Could not configure metadata read timeout",ex);
        } finally {
            DataSourceUtils.releaseConnection(connection,source);
        }
    }

    private static final class Scope implements AutoCloseable {
        private final Connection connection;
        private final int previous;
        private final boolean changed;
        Scope(Connection connection,int minimumMillis) throws SQLException {
            this.connection=connection;
            previous=connection.getNetworkTimeout();
            // Zero already means unlimited; do not shorten another caller's larger timeout.
            changed=previous>0&&previous<minimumMillis;
            if(changed)connection.setNetworkTimeout(Runnable::run,minimumMillis);
        }
        @Override public void close() throws SQLException {
            if(changed)connection.setNetworkTimeout(Runnable::run,previous);
        }
    }
}
