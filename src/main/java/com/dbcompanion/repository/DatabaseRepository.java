package com.dbcompanion.repository;

import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.TableInfo;
import com.dbcompanion.model.ColumnInfo;
import com.dbcompanion.model.AnnotationInfo;
import java.util.List;
import java.util.HashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DatabaseRepository {
    private final JdbcTemplate jdbc;

    public DatabaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.setQueryTimeout(10);
    }

    public List<com.dbcompanion.model.AiProfile> profiles(String schema, boolean own, String name) {
        String sql = "SELECT PROFILE_NAME, STATUS, DESCRIPTION, LAST_MODIFIED, PROFILE_ID, CREATED FROM "
                + (own ? "USER_CLOUD_AI_PROFILES WHERE 1=1" : "DBA_CLOUD_AI_PROFILES WHERE OWNER = ?");
        var args = new java.util.ArrayList<Object>();
        if (!own) args.add(schema);
        if (name != null) { sql += " AND PROFILE_NAME = ?"; args.add(name); }
        sql += " ORDER BY PROFILE_NAME";
        return jdbc.query(sql, (row, index) -> new com.dbcompanion.model.AiProfile(
                row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                row.getString(5), row.getString(6)), args.toArray());
    }

    public List<com.dbcompanion.model.AiProfileAttribute> profileAttributes(String schema, boolean own, String name) {
        String sql = "SELECT ATTRIBUTE_NAME, ATTRIBUTE_VALUE FROM "
                + (own ? "USER_CLOUD_AI_PROFILE_ATTRIBUTES" : "DBA_CLOUD_AI_PROFILE_ATTRIBUTES")
                + " WHERE PROFILE_NAME = ?" + (own ? "" : " AND OWNER = ?") + " ORDER BY ATTRIBUTE_NAME";
        Object[] args = own ? new Object[]{name} : new Object[]{name, schema};
        return jdbc.query(sql, (row, index) -> new com.dbcompanion.model.AiProfileAttribute(
                row.getString(1), row.getString(2)), args);
    }

    public DatabaseInfo info() {
        return jdbc.queryForObject("""
                SELECT SYS_CONTEXT('USERENV', 'SESSION_USER'),
                       SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'),
                       SYS_CONTEXT('USERENV', 'SERVICE_NAME'),
                       SYS_CONTEXT('USERENV', 'DB_NAME') FROM SYS.DUAL
                """, (row, index) -> new DatabaseInfo(row.getString(1), row.getString(2),
                row.getString(3), row.getString(4)));
    }

    public String profileObjectList(String schema, boolean own, String name) {
        String sql = "SELECT ATTRIBUTE_VALUE FROM "
                + (own ? "USER_CLOUD_AI_PROFILE_ATTRIBUTES" : "DBA_CLOUD_AI_PROFILE_ATTRIBUTES")
                + " WHERE PROFILE_NAME = ? AND ATTRIBUTE_NAME = 'object_list'"
                + (own ? "" : " AND OWNER = ?");
        Object[] args = own ? new Object[]{name} : new Object[]{name, schema};
        var values = jdbc.queryForList(sql, String.class, args);
        return values.isEmpty() ? null : values.getFirst();
    }

    public List<String> accessibleSchemas() {
        return jdbc.queryForList("""
                SELECT u.USERNAME
                FROM SYS.ALL_USERS u
                WHERE u.USERNAME = SYS_CONTEXT('USERENV', 'SESSION_USER')
                   OR EXISTS (SELECT 1 FROM SYS.ALL_OBJECTS o WHERE o.OWNER = u.USERNAME)
                ORDER BY u.USERNAME
                """, String.class);
    }

    public List<TableInfo> tables(String schema) {
        var names = jdbc.queryForList("""
                SELECT DISTINCT OBJECT_NAME FROM SYS.ALL_OBJECTS
                WHERE OWNER = ? AND SUBOBJECT_NAME IS NULL
                  AND OBJECT_TYPE IN ('TABLE', 'VIEW') ORDER BY OBJECT_NAME
                """, String.class, schema);
        if (names.isEmpty()) return List.of();
        var comments = new HashMap<String, String>();
        jdbc.query("""
                SELECT TABLE_NAME, COMMENTS FROM SYS.ALL_TAB_COMMENTS
                WHERE OWNER = ? AND TABLE_TYPE IN ('TABLE', 'VIEW') AND COMMENTS IS NOT NULL
                """, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                comments.put(row.getString(1), row.getString(2)), schema);
        return names.stream().map(name -> new TableInfo(name, comments.get(name))).toList();
    }

    public TableInfo table(String schema, String table) {
        var names = jdbc.queryForList("""
                SELECT DISTINCT OBJECT_NAME FROM SYS.ALL_OBJECTS
                WHERE OWNER = ? AND OBJECT_NAME = ? AND SUBOBJECT_NAME IS NULL
                  AND OBJECT_TYPE IN ('TABLE', 'VIEW')
                """, String.class, schema, table);
        if (names.isEmpty()) return null;
        var comments = jdbc.queryForList("""
                SELECT COMMENTS FROM SYS.ALL_TAB_COMMENTS
                WHERE OWNER = ? AND TABLE_NAME = ? AND TABLE_TYPE IN ('TABLE', 'VIEW')
                """, String.class, schema, table);
        return new TableInfo(names.getFirst(), comments.isEmpty() ? null : comments.getFirst());
    }

    public List<ColumnInfo> columns(String schema, String table) {
        var comments = new HashMap<String, String>();
        jdbc.query("""
                SELECT COLUMN_NAME, COMMENTS FROM SYS.ALL_COL_COMMENTS
                WHERE OWNER = ? AND TABLE_NAME = ? AND COMMENTS IS NOT NULL
                """, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                comments.put(row.getString(1), row.getString(2)), schema, table);
        return jdbc.query("""
                SELECT c.COLUMN_ID, c.COLUMN_NAME,
                       CASE
                         WHEN c.DATA_TYPE IN ('VARCHAR2', 'CHAR') THEN c.DATA_TYPE || '(' ||
                           CASE WHEN c.CHAR_USED = 'C' THEN c.CHAR_LENGTH || ' CHAR'
                                ELSE c.DATA_LENGTH || ' BYTE' END || ')'
                         WHEN c.DATA_TYPE IN ('NVARCHAR2', 'NCHAR') THEN c.DATA_TYPE || '(' || c.CHAR_LENGTH || ')'
                         WHEN c.DATA_TYPE = 'NUMBER' AND c.DATA_PRECISION IS NOT NULL THEN
                           'NUMBER(' || c.DATA_PRECISION || ',' || NVL(c.DATA_SCALE, 0) || ')'
                         ELSE c.DATA_TYPE END AS TYPE_LABEL,
                       c.NULLABLE
                FROM SYS.ALL_TAB_COLUMNS c
                WHERE c.OWNER = ? AND c.TABLE_NAME = ?
                ORDER BY c.COLUMN_ID
                """, (row, index) -> new ColumnInfo(row.getInt(1), row.getString(2), row.getString(3),
                row.getString(4), comments.get(row.getString(2))), schema, table);
    }

    public boolean isView(String schema, String name) {
        return !jdbc.queryForList("SELECT VIEW_NAME FROM SYS.ALL_VIEWS WHERE OWNER = ? AND VIEW_NAME = ?",
                String.class, schema, name).isEmpty();
    }

    public List<AnnotationInfo> annotations(String schema, String table) {
        return annotations(schema,table,isView(schema,table) ? "VIEW" : "TABLE");
    }
    public record ObjectMetadata(String name,String type,String comment) {}
    public ObjectMetadata objectMetadata(String schema,String name){
        var types=jdbc.queryForList("SELECT DISTINCT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL AND OBJECT_TYPE IN ('TABLE','VIEW','MATERIALIZED VIEW','SYNONYM') ORDER BY OBJECT_TYPE",String.class,schema,name);
        if(types.isEmpty())return null;
        var comments=jdbc.queryForList("SELECT COMMENTS FROM SYS.ALL_TAB_COMMENTS WHERE OWNER=? AND TABLE_NAME=?",String.class,schema,name);
        return new ObjectMetadata(name,types.getFirst(),comments.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null));
    }
    public List<AnnotationInfo> annotations(String schema, String table,String objectType) {
        return jdbc.query("""
                SELECT COLUMN_NAME, ANNOTATION_NAME, ANNOTATION_VALUE, DOMAIN_OWNER, DOMAIN_NAME
                FROM SYS.ALL_ANNOTATIONS_USAGE
                WHERE ANNOTATION_OWNER = ? AND OBJECT_NAME = ? AND OBJECT_TYPE = ?
                ORDER BY COLUMN_NAME NULLS FIRST, ANNOTATION_NAME
                """, (row, index) -> new AnnotationInfo(row.getString(1), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5)), schema, table,objectType);
    }
}
