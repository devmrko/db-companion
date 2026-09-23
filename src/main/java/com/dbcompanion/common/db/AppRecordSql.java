package com.dbcompanion.common.db;

/** General-purpose JSON records. This is not the before/after change-audit table. */
public final class AppRecordSql {
    private AppRecordSql() {}
    public static final String TABLE="DBC_APP_RECORD";
    public static String table(String schema){return ProfileHistorySql.object(schema,TABLE);}
    public static String create(String schema){return "CREATE TABLE "+table(schema)+"""
        (SEQ NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
         RECORD_ID VARCHAR2(36 CHAR) NOT NULL UNIQUE,
         RECORD_TYPE VARCHAR2(32 CHAR) NOT NULL,
         FORMAT_VERSION NUMBER DEFAULT 1 NOT NULL CONSTRAINT DBC_APP_RECORD_V1 CHECK (FORMAT_VERSION=1),
         STATE VARCHAR2(24 CHAR) NOT NULL,
         ACTOR VARCHAR2(128 CHAR) DEFAULT SYS_CONTEXT('USERENV','SESSION_USER') NOT NULL,
         RECORDED_AT TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
         UPDATED_AT TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
         PAYLOAD CLOB NOT NULL CONSTRAINT DBC_APP_RECORD_JSON CHECK (PAYLOAD IS JSON))
        """;}
}
