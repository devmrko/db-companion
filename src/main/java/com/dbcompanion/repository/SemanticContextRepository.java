package com.dbcompanion.repository;

import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.model.SemanticContext;
import com.dbcompanion.model.SemanticContext.*;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SemanticContextRepository {
    private final JdbcTemplate jdbc;
    public SemanticContextRepository(JdbcTemplate template) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(template.getDataSource())); jdbc.setQueryTimeout(10);
    }
    public Capability capability() {
        String version = "";
        try {
            version = jdbc.queryForObject("SELECT VERSION_FULL FROM PRODUCT_COMPONENT_VERSION WHERE PRODUCT LIKE 'Oracle%Database%' FETCH FIRST 1 ROW ONLY", String.class);
            if (!SemanticContext.versionSupported(version)) return new Capability(version, false, "version", "");
            var owners = jdbc.queryForList("""
                    SELECT s.TABLE_OWNER FROM SYS.ALL_SYNONYMS s JOIN SYS.ALL_OBJECTS o
                      ON o.OWNER=s.TABLE_OWNER AND o.OBJECT_NAME=s.TABLE_NAME AND o.OBJECT_TYPE='PACKAGE' AND o.STATUS='VALID'
                    WHERE s.OWNER='PUBLIC' AND s.SYNONYM_NAME='DBMS_AI_SEM' AND s.TABLE_NAME='DBMS_AI_SEM' AND s.DB_LINK IS NULL
                    """, String.class);
            if (owners.size() != 1) return new Capability(version, false, "api", "");
            String owner = owners.getFirst();
            var args = jdbc.queryForList("""
                    SELECT ARGUMENT_NAME || ':' || DATA_TYPE || ':' || IN_OUT FROM SYS.ALL_ARGUMENTS
                    WHERE OWNER=? AND PACKAGE_NAME='DBMS_AI_SEM' AND OBJECT_NAME='CREATE_SEMANTIC_SOURCE'
                    ORDER BY SEQUENCE
                    """, String.class, owner);
            if (!args.equals(List.of("SOURCE_NAME:VARCHAR2:IN", "TYPE:VARCHAR2:IN", "ATTRIBUTES:CLOB:IN", "STATUS:VARCHAR2:IN", "DESCRIPTION:CLOB:IN")))
                return new Capability(version, false, "api", "");
            jdbc.queryForList("SELECT SOURCE_NAME,SOURCE_TYPE,STATUS,DESCRIPTION FROM " + object(owner, "USER_AI_SEMANTIC_SOURCES") + " WHERE 1=0");
            jdbc.queryForList("SELECT SOURCE_NAME,ATTRIBUTE_NAME,ATTRIBUTE_VALUE FROM " + object(owner, "USER_AI_SEMANTIC_SOURCE_ATTRIBUTES") + " WHERE 1=0");
            return new Capability(version, true, "supported", owner);
        } catch (org.springframework.dao.DataAccessException ex) { return new Capability(version, false, "unavailable", ""); }
    }
    public Capability require() {
        var capability = capability();
        if (!capability.supported()) throw SemanticContext.error(409, "unsupported");
        return capability;
    }
    public List<Source> sources(String owner) {
        var callbacks = new java.util.HashMap<String,java.util.Map<String,String>>();
        jdbc.query("SELECT SOURCE_NAME,ATTRIBUTE_NAME,ATTRIBUTE_VALUE FROM " + object(owner,"USER_AI_SEMANTIC_SOURCE_ATTRIBUTES")
                + " WHERE LOWER(ATTRIBUTE_NAME) IN ('scope','global_callback_func','object_callback_func')", (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                    callbacks.computeIfAbsent(r.getString(1), key -> new java.util.HashMap<>()).put(r.getString(2).toLowerCase(java.util.Locale.ROOT),r.getString(3));
                });
        return jdbc.query("SELECT SOURCE_NAME,SOURCE_TYPE,STATUS,DESCRIPTION FROM " + object(owner, "USER_AI_SEMANTIC_SOURCES")
                + " ORDER BY SOURCE_NAME FETCH FIRST 1001 ROWS ONLY", (r, i) -> new Source(r.getString(1), r.getString(2), r.getString(3), r.getString(4),callbacks.getOrDefault(r.getString(1),java.util.Map.of())));
    }
    public void requireSources(String value) {
        var names = SemanticContext.sources(value); var capability = require();
        for (var name : names) {
            // This editor deliberately binds sources owned by the logged-in profile owner.
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + object(capability.owner(), "USER_AI_SEMANTIC_SOURCES")
                    + " WHERE SOURCE_NAME=? AND UPPER(STATUS)='ENABLED'", Integer.class, name.stringValue());
            if (count == null || count != 1) throw SemanticContext.error(409, "sourceUnavailable");
        }
    }
    public void create(String owner, Create input) {
        jdbc.update("BEGIN " + object(owner, "DBMS_AI_SEM")
                + ".CREATE_SEMANTIC_SOURCE(source_name=>?,type=>?,attributes=>?,status=>?,description=>?); END;", statement -> {
                    statement.setString(1, input.name()); statement.setString(2, input.type());
                    statement.setClob(3, new java.io.StringReader(input.attributes())); statement.setString(4, "enabled");
                    statement.setClob(5, new java.io.StringReader(input.description()));
                });
    }
    public boolean matches(String owner, Create input, tools.jackson.databind.JsonNode expected) {
        var rows = sources(owner).stream().filter(s -> s.name().equals(input.name())).toList();
        if (rows.size() != 1 || !rows.getFirst().type().equalsIgnoreCase(input.type()) || !rows.getFirst().status().equalsIgnoreCase("enabled")
                || !Objects.toString(rows.getFirst().description(), "").equals(input.description())) return false;
        var values = jdbc.query("SELECT ATTRIBUTE_NAME,ATTRIBUTE_VALUE FROM " + object(owner, "USER_AI_SEMANTIC_SOURCE_ATTRIBUTES")
                + " WHERE SOURCE_NAME=?", (r, i) -> new String[]{r.getString(1), r.getString(2)}, input.name());
        var json = new tools.jackson.databind.json.JsonMapper();
        for (var entry : expected.properties()) {
            var actual = values.stream().filter(v -> v[0].equalsIgnoreCase(entry.getKey())).findFirst();
            if (actual.isEmpty()) return false;
            String value = actual.get()[1];
            if (entry.getValue().isString()) {
                if (!attributeEquals(entry.getKey(),entry.getValue().stringValue(),value)) return false;
            } else if (!entry.getValue().equals(json.readTree(value))) return false;
        }
        return true;
    }
    public static boolean attributeEquals(String key,String expected,String actual) {
        if (Objects.equals(expected,actual)) return true;
        if (!List.of("global_callback_func","object_callback_func").contains(key) || actual==null) return false;
        return expected.equals(actual.replaceAll("\"([A-Z][A-Z0-9_$#]*)\"", "$1"));
    }
    public List<String> accessUsers() {
        return jdbc.queryForList("SELECT USERNAME FROM SYS.ALL_USERS WHERE ORACLE_MAINTAINED='N' AND USERNAME<>'ADMIN' ORDER BY USERNAME",String.class);
    }
    public boolean accessGranted(String owner,String username) {
        if (!accessUsers().contains(username)) throw SemanticContext.error(400,"accessUserInvalid");
        return jdbc.queryForObject("SELECT COUNT(*) FROM SYS.DBA_TAB_PRIVS WHERE OWNER=? AND TABLE_NAME='DBMS_AI_SEM' AND GRANTEE=? AND PRIVILEGE='EXECUTE'",Integer.class,owner,username)>0;
    }
    public static String accessSql(String owner,String username) {
        return "GRANT EXECUTE ON "+object(owner,"DBMS_AI_SEM")+" TO "+com.dbcompanion.common.db.MetadataSql.identifier(username);
    }
    public void grantAccess(String owner,String username) {
        if (accessGranted(owner,username)) throw SemanticContext.error(409,"accessAlready");
        jdbc.execute(accessSql(owner,username));
    }
    private static String object(String owner, String name) { return ProfileHistorySql.object(owner, name); }
}
