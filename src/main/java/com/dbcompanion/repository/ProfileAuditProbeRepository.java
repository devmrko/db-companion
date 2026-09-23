package com.dbcompanion.repository;

import com.dbcompanion.common.db.ProfileAuditProbeSql;
import com.dbcompanion.common.db.ProfileAuditProbeSql.Run;
import com.dbcompanion.common.db.InstructionAuditProbe;
import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("profile-audit-diagnostics")
public class ProfileAuditProbeRepository {
    private final JdbcTemplate jdbc;
    public ProfileAuditProbeRepository(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(10);
    }
    public record Identity(String user, String database, String service, String sessionId, String instanceId,
                           String previousClientId, String packageOwner) {}
    public Identity preflight() {
        var identity = jdbc.queryForMap("""
                SELECT SYS_CONTEXT('USERENV','SESSION_USER') LOGIN_USER,
                  SYS_CONTEXT('USERENV','DB_NAME') DB_NAME, SYS_CONTEXT('USERENV','SERVICE_NAME') SERVICE_NAME,
                  SYS_CONTEXT('USERENV','SESSIONID') SESSION_ID, SYS_CONTEXT('USERENV','INSTANCE') INSTANCE_ID,
                  SYS_CONTEXT('USERENV','CLIENT_IDENTIFIER') CLIENT_ID FROM SYS.DUAL
                """);
        String user = (String) identity.get("LOGIN_USER");
        var privileges = jdbc.queryForList("SELECT PRIVILEGE FROM SESSION_PRIVS WHERE PRIVILEGE='AUDIT SYSTEM'", String.class);
        var roles = jdbc.queryForList("SELECT ROLE FROM SESSION_ROLES WHERE ROLE='AUDIT_ADMIN'", String.class);
        if (privileges.isEmpty() && roles.isEmpty()) throw new IllegalStateException("AUDIT SYSTEM or AUDIT_ADMIN is required; no privilege was granted");
        var owners = jdbc.queryForList("""
                SELECT s.TABLE_OWNER FROM SYS.ALL_SYNONYMS s
                JOIN SYS.ALL_OBJECTS o ON o.OWNER=s.TABLE_OWNER AND o.OBJECT_NAME=s.TABLE_NAME
                WHERE s.SYNONYM_NAME='DBMS_CLOUD_AI' AND s.OWNER IN (?, 'PUBLIC') AND s.DB_LINK IS NULL
                  AND s.TABLE_NAME='DBMS_CLOUD_AI' AND o.OBJECT_TYPE='PACKAGE'
                  AND o.STATUS='VALID' AND o.ORACLE_MAINTAINED='Y'
                ORDER BY CASE WHEN s.OWNER=? THEN 0 ELSE 1 END
                """, String.class, user, user);
        if (owners.isEmpty()) throw new IllegalStateException("No valid Oracle-maintained DBMS_CLOUD_AI package was resolved");
        // Compile only: check the exact views/columns before any CREATE.
        jdbc.queryForList("SELECT POLICY_NAME, AUDIT_CONDITION, CONDITION_EVAL_OPT, AUDIT_OPTION, OBJECT_SCHEMA, OBJECT_NAME FROM SYS.AUDIT_UNIFIED_POLICIES WHERE 1=0");
        jdbc.queryForList("SELECT POLICY_NAME, ENTITY_NAME, ENTITY_TYPE, ENABLED_OPTION, SUCCESS, FAILURE FROM SYS.AUDIT_UNIFIED_ENABLED_POLICIES WHERE 1=0");
        jdbc.queryForList("SELECT EVENT_TIMESTAMP, DBUSERNAME, CLIENT_IDENTIFIER, SESSIONID, OBJECT_SCHEMA, OBJECT_NAME, ACTION_NAME, RETURN_CODE, SQL_TEXT, SQL_BINDS, UNIFIED_AUDIT_POLICIES FROM UNIFIED_AUDIT_TRAIL WHERE 1=0");
        jdbc.queryForList("SELECT PROFILE_ID, PROFILE_NAME, STATUS, DESCRIPTION FROM USER_CLOUD_AI_PROFILES WHERE 1=0");
        jdbc.queryForList("SELECT ATTRIBUTE_NAME, ATTRIBUTE_VALUE, LAST_MODIFIED FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES WHERE 1=0");
        return new Identity(user, (String) identity.get("DB_NAME"), (String) identity.get("SERVICE_NAME"),
                (String) identity.get("SESSION_ID"), (String) identity.get("INSTANCE_ID"),
                (String) identity.get("CLIENT_ID"), owners.getFirst());
    }
    public void execute(String sql) { jdbc.execute(sql); }
    public void comments(Run run, String owner, String value) {
        jdbc.update(ProfileAuditProbeSql.boundComments(owner), run.profile(), value);
    }
    public void instruction(Run run, String owner, InstructionAuditProbe.Input input) {
        String sql = InstructionAuditProbe.sql(run, owner, input);
        if (input.transport() == InstructionAuditProbe.Transport.LITERAL) { execute(sql); return; }
        jdbc.update(sql, statement -> {
            if (input.transport() == InstructionAuditProbe.Transport.CLOB)
                statement.setClob(1, new StringReader(input.value()), input.value().length());
            else statement.setString(1, input.value());
        });
    }
    public void clientId(String value) {
        if (value == null) execute("BEGIN SYS.DBMS_SESSION.CLEAR_IDENTIFIER; END;");
        else jdbc.update("BEGIN SYS.DBMS_SESSION.SET_IDENTIFIER(?); END;", value);
    }
    public List<Map<String, String>> policy(Run run) {
        return jdbc.query("""
                SELECT POLICY_NAME, AUDIT_CONDITION, CONDITION_EVAL_OPT, AUDIT_OPTION, AUDIT_OPTION_TYPE,
                  OBJECT_SCHEMA, OBJECT_NAME, OBJECT_TYPE FROM SYS.AUDIT_UNIFIED_POLICIES
                WHERE POLICY_NAME=? ORDER BY AUDIT_OPTION, OBJECT_SCHEMA, OBJECT_NAME
                """, (r, n) -> row(r), run.policy());
    }
    public void requireEnabled(Run run, String user) {
        var rows = jdbc.queryForList("""
                SELECT ENTITY_NAME, ENTITY_TYPE, ENABLED_OPTION, SUCCESS, FAILURE
                FROM SYS.AUDIT_UNIFIED_ENABLED_POLICIES WHERE POLICY_NAME=?
                """, run.policy());
        if (rows.size() != 1 || !user.equals(rows.getFirst().get("ENTITY_NAME"))
                || !"USER".equals(rows.getFirst().get("ENTITY_TYPE"))
                || !"BY USER".equals(rows.getFirst().get("ENABLED_OPTION"))
                || !"YES".equals(rows.getFirst().get("SUCCESS")) || !"YES".equals(rows.getFirst().get("FAILURE")))
            throw new IllegalStateException("Audit policy enablement did not match the diagnostic user: " + rows);
    }
    public boolean enabled(Run run) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM SYS.AUDIT_UNIFIED_ENABLED_POLICIES WHERE POLICY_NAME=?", Long.class, run.policy()) > 0;
    }
    public List<Map<String, String>> profile(Run run) {
        return jdbc.query("SELECT PROFILE_ID, PROFILE_NAME, STATUS, DESCRIPTION FROM USER_CLOUD_AI_PROFILES WHERE PROFILE_NAME=?",
                (r, n) -> row(r), run.profile());
    }
    public List<Map<String, String>> attributes(Run run) {
        return jdbc.query("""
                SELECT ATTRIBUTE_NAME, ATTRIBUTE_VALUE, TO_CHAR(LAST_MODIFIED, 'YYYY-MM-DD HH24:MI:SS.FF TZH:TZM') LAST_MODIFIED
                FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES WHERE PROFILE_NAME=? ORDER BY ATTRIBUTE_NAME
                """, (r, n) -> row(r), run.profile());
    }
    public List<Map<String, String>> evidence(Run run, String user, String owner) {
        return jdbc.query("""
                SELECT TO_CHAR(EVENT_TIMESTAMP, 'YYYY-MM-DD HH24:MI:SS.FF') EVENT_TIMESTAMP,
                  DBUSERNAME, CLIENT_IDENTIFIER, SESSIONID, OBJECT_SCHEMA, OBJECT_NAME, ACTION_NAME,
                  RETURN_CODE, SQL_TEXT, SQL_BINDS, UNIFIED_AUDIT_POLICIES
                FROM UNIFIED_AUDIT_TRAIL
                WHERE EVENT_TIMESTAMP >= SYSTIMESTAMP - INTERVAL '1' DAY
                  AND CLIENT_IDENTIFIER=? AND DBUSERNAME=? AND OBJECT_SCHEMA=? AND OBJECT_NAME='DBMS_CLOUD_AI'
                ORDER BY EVENT_TIMESTAMP, ENTRY_ID FETCH FIRST 50 ROWS ONLY
                """, (r, n) -> row(r), run.client(), user, owner);
    }
    private static Map<String, String> row(ResultSet r) throws SQLException {
        var row = new LinkedHashMap<String, String>();
        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
            String text = r.getString(i);
            row.put(r.getMetaData().getColumnLabel(i), text != null && text.length() > 65536 ? text.substring(0, 65536) + " [TRUNCATED]" : text);
        }
        return Collections.unmodifiableMap(row);
    }
}
