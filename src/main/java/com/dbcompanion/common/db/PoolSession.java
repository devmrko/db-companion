package com.dbcompanion.common.db;

import com.zaxxer.hikari.HikariDataSource;
import com.dbcompanion.model.DatabaseSession;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;

public final class PoolSession implements AutoCloseable, HttpSessionBindingListener {
    public static final String ATTRIBUTE = "databaseSession";
    private final HikariDataSource pool;
    private final String alias;
    private final String walletId;
    private final String walletName;
    private final Runnable onClose;
    private boolean closed;
    private DatabaseSession metadata;

    public PoolSession(HikariDataSource pool, String alias, Runnable onClose) {
        this(pool, alias, "", "", onClose);
    }

    public PoolSession(HikariDataSource pool, String alias, String walletId, String walletName, Runnable onClose) {
        this.pool = pool;
        this.alias = alias;
        this.walletId = walletId;
        this.walletName = walletName;
        this.onClose = onClose;
    }

    public String alias() { return alias; }
    public String walletId() { return walletId; }
    public String walletName() { return walletName; }

    public synchronized void initialize(DatabaseSession metadata) {
        if (closed || this.metadata != null) throw new IllegalStateException("Database session already initialized or closed");
        this.metadata = java.util.Objects.requireNonNull(metadata);
    }

    public synchronized DatabaseSession metadata() {
        if (closed || metadata == null) throw new IllegalStateException("Database session not available");
        return metadata;
    }

    /** Null only during the initial login metadata query. */
    public synchronized String connectionSchema() {
        if (closed) throw new IllegalStateException("Database session closed");
        return metadata == null ? null : metadata.selectedSchema();
    }

    public synchronized HikariDataSource pool() {
        if (closed) throw new IllegalStateException("Database session closed");
        return pool;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { pool.close(); }
        finally { onClose.run(); }
    }

    @Override
    public void valueUnbound(HttpSessionBindingEvent event) { close(); }
}
