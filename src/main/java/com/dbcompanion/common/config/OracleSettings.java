package com.dbcompanion.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.oracle")
public record OracleSettings(String walletPath, String walletPaths) {
    public boolean configured() {
        return walletPaths != null && !walletPaths.isBlank() || walletPath != null && !walletPath.isBlank();
    }
}
