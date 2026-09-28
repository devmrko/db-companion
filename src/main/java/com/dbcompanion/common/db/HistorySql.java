package com.dbcompanion.common.db;

import com.dbcompanion.model.MetadataEdit.Target;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/** Fixed application assets. Never accepts DDL from the request. */
public final class HistorySql {
    private HistorySql() {}
    public record Asset(String owner, String name, String type, String sql) {}
    public static String qualified(String owner, String name) { return MetadataSql.identifier(owner) + "." + MetadataSql.identifier(name); }
    public static String literal(String value) { MetadataSql.identifier(value); return "'" + value.replace("'", "''") + "'"; }
    public static String triggerName(Target target, boolean before) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256").digest((target.schema() + "\0" + target.table()).getBytes(StandardCharsets.UTF_8));
            return "DBC_MH_" + (before ? "B_" : "A_") + HexFormat.of().formatHex(bytes).substring(0, 20).toUpperCase(java.util.Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static List<Asset> assets(Target target, String triggerOwner) {
        String owner = target.schema();
        String history = qualified(owner, "DBC_METADATA_HISTORY");
        return List.of(
            new Asset(owner, "DBC_METADATA_TRACKING", "TABLE", "CREATE TABLE " + qualified(owner, "DBC_METADATA_TRACKING") + " ("
                + "TABLE_NAME VARCHAR2(128 BYTE) NOT NULL, ENABLED CHAR(1 BYTE) DEFAULT 'N' NOT NULL, "
                + "CHANGED_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL, CHANGED_BY VARCHAR2(128 BYTE) NOT NULL, "
                + "TRIGGER_OWNER VARCHAR2(128 BYTE), "
                + "CONSTRAINT DBC_META_TRACK_PK PRIMARY KEY (TABLE_NAME), CONSTRAINT DBC_META_TRACK_CK CHECK (ENABLED IN ('Y','N')))"),
            new Asset(owner, "DBC_METADATA_HISTORY", "TABLE", "CREATE TABLE " + history + " ("
                + "SEQ NUMBER GENERATED ALWAYS AS IDENTITY, EVENT_ID RAW(16) NOT NULL, CHANGED_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL, "
                + "CHANGED_BY VARCHAR2(128 BYTE) NOT NULL, SCHEMA_NAME VARCHAR2(128 BYTE) NOT NULL, TABLE_NAME VARCHAR2(128 BYTE) NOT NULL, "
                + "COLUMN_NAME VARCHAR2(128 BYTE), CHANGE_KIND VARCHAR2(16 BYTE) NOT NULL, ANNOTATION_NAME VARCHAR2(4000 BYTE), "
                + "BEFORE_JSON CLOB NOT NULL, AFTER_JSON CLOB NOT NULL, CONSTRAINT DBC_META_HIST_PK PRIMARY KEY (SEQ), "
                + "CONSTRAINT DBC_META_BEFORE_JSON CHECK (BEFORE_JSON IS JSON), CONSTRAINT DBC_META_AFTER_JSON CHECK (AFTER_JSON IS JSON))"),
            new Asset(owner, "DBC_META_HIST_LOOKUP", "INDEX", "CREATE INDEX " + qualified(owner, "DBC_META_HIST_LOOKUP") + " ON " + history + " (TABLE_NAME, SEQ)"),
            new Asset(owner, "DBC_METADATA_AUDIT", "PACKAGE", resource("audit-spec.sql", owner)),
            new Asset(owner, "DBC_METADATA_AUDIT", "PACKAGE BODY", resource("audit-body.sql", owner)),
            trigger(target, triggerOwner, false), trigger(target, triggerOwner, true));
    }
    public static Asset trigger(Target target, String triggerOwner, boolean before) {
        return metadataTrigger(target, triggerOwner, before, false);
    }
    /** Exact v2 source remains recognizable for explicit OFF and migration. */
    public static Asset tableTriggerV2(Target target, String triggerOwner, boolean before) {
        return metadataTrigger(target, triggerOwner, before, true);
    }
    private static Asset metadataTrigger(Target target, String triggerOwner, boolean before, boolean tableOnly) {
        String name = triggerName(target, before);
        String table = literal(target.table()), owner = literal(target.schema());
        String sql = "CREATE TRIGGER " + qualified(triggerOwner, name) + "\n"
                + (before ? "BEFORE" : "AFTER") + " COMMENT OR ALTER ON DATABASE\nDISABLE\n"
                + "-- DB Companion metadata history v" + (tableOnly ? "2" : "3") + "\n"
                + "DECLARE\n  v_match BOOLEAN := FALSE;\n  v_count PLS_INTEGER;\n"
                + "  v_name VARCHAR2(32767) := ora_dict_obj_name;\n  v_column VARCHAR2(32767);\nBEGIN\n"
                + "  IF ora_dict_obj_owner = " + owner + " THEN\n"
                + "    IF " + (tableOnly ? "ora_dict_obj_type = 'TABLE'" : "ora_dict_obj_type IN ('TABLE', 'VIEW')")
                + " AND v_name = " + table + " THEN\n      v_match := TRUE;\n"
                + "    ELSIF ora_sysevent = 'COMMENT' AND ora_dict_obj_type = 'COLUMN'\n"
                + "      AND SUBSTR(v_name, 1, LENGTH(" + table + ") + 1) = " + table + " || '.' THEN\n"
                + "      SELECT COUNT(*) INTO v_count FROM sys.all_tab_columns\n"
                + "        WHERE owner = " + owner + " AND table_name = " + table + "\n"
                + "          AND table_name || '.' || column_name = v_name;\n"
                + "      IF v_count = 1 THEN\n"
                + "        v_column := SUBSTR(v_name, LENGTH(" + table + ") + 2);\n"
                + "        IF INSTR(" + table + ", '.') > 0 OR INSTR(v_column, '.') > 0 THEN\n"
                + "          SELECT COUNT(*) INTO v_count FROM sys.all_tab_columns\n"
                + "            WHERE owner = " + owner + " AND table_name <> " + table + "\n"
                + "              AND table_name || '.' || column_name = v_name;\n"
                + "          IF v_count > 0 THEN RAISE_APPLICATION_ERROR(-20084, 'Ambiguous column metadata event'); END IF;\n"
                + "        END IF;\n        v_match := TRUE;\n      END IF;\n    END IF;\n  END IF;\n"
                + "  IF v_match THEN\n    " + qualified(target.schema(), "DBC_METADATA_AUDIT")
                + (before ? ".capture_before(" : ".capture_after(") + table + ");\n  END IF;\nEND;";
        return new Asset(triggerOwner, name, "TRIGGER", sql);
    }
    /** Exact historical definition, only for explicit migration and safe OFF. */
    public static Asset legacyTrigger(Target target, String triggerOwner, boolean before) {
        String name = triggerName(target, before);
        return new Asset(triggerOwner, name, "TRIGGER", "CREATE TRIGGER " + qualified(triggerOwner, name) + "\n"
                + (before ? "BEFORE" : "AFTER") + " COMMENT OR ALTER ON DATABASE\nDISABLE\n"
                + "-- DB Companion metadata history v1\nBEGIN\n"
                + "  IF ora_dict_obj_owner = " + literal(target.schema()) + " AND ora_dict_obj_name = " + literal(target.table())
                + " AND ora_dict_obj_type = 'TABLE' THEN\n    " + qualified(target.schema(), "DBC_METADATA_AUDIT")
                + (before ? ".capture_before(" : ".capture_after(") + literal(target.table()) + ");\n  END IF;\nEND;");
    }
    public enum TriggerVersion { CURRENT, LEGACY, UNKNOWN }
    public static TriggerVersion triggerVersion(Target target, String owner, boolean before, String actual) {
        if (sourceMatches(trigger(target, owner, before), actual)) return TriggerVersion.CURRENT;
        if (sourceMatches(legacyTrigger(target, owner, before), actual)) return TriggerVersion.LEGACY;
        if (sourceMatches(tableTriggerV2(target, owner, before), actual)) return TriggerVersion.LEGACY;
        return TriggerVersion.UNKNOWN;
    }
    public static String replaceTrigger(Target target, String owner, boolean before) {
        return trigger(target, owner, before).sql().replaceFirst("\\ACREATE TRIGGER ", "CREATE OR REPLACE TRIGGER ");
    }
    public static String switchTrigger(Target target, String triggerOwner, boolean before, boolean enabled) {
        return "ALTER TRIGGER " + qualified(triggerOwner, triggerName(target, before)) + (enabled ? " ENABLE" : " DISABLE");
    }
    public static String upgradeTracking(Target target) {
        return "ALTER TABLE " + qualified(target.schema(), "DBC_METADATA_TRACKING") + " ADD (TRIGGER_OWNER VARCHAR2(128 BYTE))";
    }
    public static String compileAuditBody(Target target) {
        return "ALTER PACKAGE " + qualified(target.schema(), "DBC_METADATA_AUDIT") + " COMPILE BODY REUSE SETTINGS";
    }
    public static Asset auditBody(Target target, boolean legacy) {
        return new Asset(target.schema(), "DBC_METADATA_AUDIT", "PACKAGE BODY",
                resource(legacy ? "audit-body-v1.sql" : "audit-body.sql", target.schema()));
    }
    public static Asset auditBodyV2(Target target) {
        return new Asset(target.schema(), "DBC_METADATA_AUDIT", "PACKAGE BODY", resource("audit-body-v2.sql", target.schema()));
    }
    public static boolean legacyAuditBodyMatches(Target target, String actual) {
        return sourceMatches(auditBody(target, true), actual) || sourceMatches(auditBodyV2(target), actual);
    }
    public static String replaceAuditBody(Target target) {
        return auditBody(target, false).sql().replaceFirst("\\ACREATE PACKAGE BODY ", "CREATE OR REPLACE PACKAGE BODY ");
    }
    public static boolean sourceMatches(Asset asset, String actual) {
        return asset.type().equals("TRIGGER")
                ? triggerSource(actual).equals(triggerSource(asset.sql()))
                : sourceBody(actual).equals(sourceBody(asset.sql()));
    }
    // ALL_SOURCE omits the enabled flag and our pre-BEGIN marker. Do not normalize executable text.
    private static String triggerSource(String source) {
        String normalized = source.replace("\r\n", "\n").stripTrailing();
        if (normalized.startsWith("CREATE ")) normalized = normalized.substring("CREATE ".length());
        return normalized.replaceFirst("\\A(TRIGGER [^\\n]+\\n(?:BEFORE|AFTER) COMMENT OR ALTER ON DATABASE\\n)"
                + "(?:(?:ENABLE|DISABLE)\\n)?(?:-- DB Companion metadata history v[123]\\n)?(?=(?:BEGIN|DECLARE)\\n)", "$1");
    }
    // ALL_SOURCE preserves the body after its first declaration line. Ignore only line-ending/trailing whitespace.
    public static String sourceBody(String source) {
        String normalized = source.replace("\r\n", "\n");
        int newline = normalized.indexOf('\n');
        return (newline < 0 ? "" : normalized.substring(newline + 1)).stripTrailing();
    }
    private static String resource(String name, String owner) {
        try {
            String sql = new ClassPathResource("db/history/" + name).getContentAsString(StandardCharsets.UTF_8);
            return sql.replace("{{OWNER}}", MetadataSql.identifier(owner)).replace("{{OWNER_LITERAL}}", literal(owner));
        } catch (java.io.IOException ex) { throw new IllegalStateException("History SQL asset is missing", ex); }
    }
}
