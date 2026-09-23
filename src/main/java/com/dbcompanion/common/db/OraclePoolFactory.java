package com.dbcompanion.common.db;

import com.dbcompanion.common.exception.AppException;
import static com.dbcompanion.common.exception.AppException.Code.*;

import com.dbcompanion.service.TnsCatalogService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

@Component
public class OraclePoolFactory {
    private final TnsCatalogService catalog;
    private final Semaphore capacity = new Semaphore(10);
    private final Map<String, PoolSession> sessions = new HashMap<>();
    private boolean stopping;

    public OraclePoolFactory(TnsCatalogService catalog) {
        this.catalog = catalog;
    }

    public PoolSession open(String username, String password, String alias) {
        return open(username, password, alias, "");
    }

    public PoolSession open(String username, String password, String alias, String walletId) {
        var selected = catalog.wallet(walletId);
        catalog.validate(selected.id(), alias);
        var wallet = selected.path();
        if (!Files.isReadable(wallet.resolve("tnsnames.ora")) || !Files.isReadable(wallet.resolve("cwallet.sso"))) {
            throw new AppException(WALLET_FILES_MISSING);
        }
        if (!capacity.tryAcquire()) throw new AppException(SESSION_LIMIT_REACHED);
        var id = UUID.randomUUID().toString();
        HikariDataSource pool = null;
        try {
            var config = new HikariConfig();
            config.setPoolName("db-" + id);
            config.setJdbcUrl("jdbc:oracle:thin:@" + alias);
            config.setUsername(username);
            config.setPassword(password);
            config.setDriverClassName("oracle.jdbc.OracleDriver");
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(10_000);
            config.setValidationTimeout(3_000);
            config.setIdleTimeout(60_000);
            config.setMaxLifetime(600_000);
            config.setInitializationFailTimeout(-1);
            config.addDataSourceProperty("oracle.net.tns_admin", wallet.toString());
            config.addDataSourceProperty("oracle.net.wallet_location", wallet.toUri().toString());
            config.addDataSourceProperty("oracle.net.ssl_server_dn_match", "true");
            config.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", "8000");
            config.addDataSourceProperty("oracle.jdbc.ReadTimeout", "15000");
            pool = new HikariDataSource(config);
            var session = new PoolSession(pool, alias, selected.id(), selected.name(), () -> release(id));
            synchronized (sessions) {
                if (stopping) throw new IllegalStateException("Application stopping");
                sessions.put(id, session);
            }
            return session;
        } catch (RuntimeException ex) {
            if (pool != null) pool.close();
            capacity.release();
            throw ex;
        }
    }

    private void release(String id) {
        synchronized (sessions) { sessions.remove(id); }
        capacity.release();
    }

    @PreDestroy
    public void shutdown() {
        java.util.List<PoolSession> remaining;
        synchronized (sessions) {
            stopping = true;
            remaining = java.util.List.copyOf(sessions.values());
        }
        remaining.forEach(PoolSession::close);
    }
}
