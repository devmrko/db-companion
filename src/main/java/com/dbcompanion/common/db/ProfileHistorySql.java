package com.dbcompanion.common.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProfileHistorySql {
    public static final String CONFIG = "DBC_PROFILE_AUDIT_CONFIG", HISTORY = "DBC_PROFILE_HISTORY";
    public static final String MARKER = "DB Manage Companion profile history v1";
    private ProfileHistorySql() {}
    public static String object(String schema, String name) { return MetadataSql.identifier(schema) + "." + MetadataSql.identifier(name); }
    public static String policy(String schema) {
        try { return "DBC_PH_" + HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(schema.getBytes(StandardCharsets.UTF_8))).substring(0, 20); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static String createPolicy(String schema, String owner) {
        return "CREATE AUDIT POLICY " + MetadataSql.identifier(policy(schema)) + " ACTIONS EXECUTE ON " + object(owner, "DBMS_CLOUD_AI");
    }
    public static String toggle(String schema, boolean enabled) {
        return (enabled ? "AUDIT" : "NOAUDIT") + " POLICY " + MetadataSql.identifier(policy(schema)) + " BY " + MetadataSql.identifier(schema);
    }
    public static Map<String, String> tables(String schema) {
        var result = new LinkedHashMap<String, String>();
        result.put(CONFIG, "CREATE TABLE " + object(schema, CONFIG) + """
                (ID NUMBER PRIMARY KEY CHECK (ID=1), VERSION NUMBER NOT NULL CHECK (VERSION=1),
                 PACKAGE_OWNER VARCHAR2(128 CHAR) NOT NULL,
                 CAPTURE_FROM TIMESTAMP DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL)
                """);
        return result;
    }
    // UTC + all audit identity fields avoids relying on USERENV.SESSIONID or a lossy time cursor.
    public static final String SOURCE_KEY = """
            RAWTOHEX(STANDARD_HASH(
              NVL(TO_CHAR(a.DBID),'~')||':'||NVL(TO_CHAR(a.INSTANCE_ID),'~')||':'||
              NVL(TO_CHAR(a.SESSIONID),'~')||':'||NVL(TO_CHAR(a.ENTRY_ID),'~')||':'||
              NVL(TO_CHAR(a.STATEMENT_ID),'~')||':'||NVL(a.EXECUTION_ID,'~')||':'||
              NVL(TO_CHAR(a.SCN),'~')||':'||TO_CHAR(a.EVENT_TIMESTAMP_UTC,'YYYY-MM-DD HH24:MI:SS.FF6'),'SHA256'))
            """;
}
