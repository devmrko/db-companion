package com.dbcompanion.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** App-local query and generation budgets; never changes database or profile settings. */
@Component
public final class SelectAiExecutionSettings {
    public static final int DEFAULT_SECONDS=300;
    public static final int MAX_SECONDS=3600;
    private final int sqlTimeoutSeconds;
    private final int generateTimeoutSeconds;

    public SelectAiExecutionSettings(int seconds){this(seconds,DEFAULT_SECONDS);}
    @org.springframework.beans.factory.annotation.Autowired
    public SelectAiExecutionSettings(@Value("${app.select-ai.sql-timeout-seconds:300}") int seconds,
            @Value("${app.select-ai.generate-timeout-seconds:300}") int generateSeconds){
        validate(seconds);validate(generateSeconds);sqlTimeoutSeconds=seconds;generateTimeoutSeconds=generateSeconds;
    }
    public static void validate(int seconds){
        if(seconds<1||seconds>MAX_SECONDS)
            throw new IllegalArgumentException("Select AI timeout seconds must be between 1 and 3600");
    }
    public int sqlTimeoutSeconds(){return sqlTimeoutSeconds;}
    public int networkTimeoutMillis(){return (sqlTimeoutSeconds+30)*1000;}
    public int transactionTimeoutSeconds(){return sqlTimeoutSeconds+60;}
    public int generateTimeoutSeconds(){return generateTimeoutSeconds;}
    public int generateNetworkTimeoutMillis(){return (generateTimeoutSeconds+30)*1000;}
    public int generateTransactionTimeoutSeconds(){return generateTimeoutSeconds+60;}
}
