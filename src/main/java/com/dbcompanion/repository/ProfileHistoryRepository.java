package com.dbcompanion.repository;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.AiHistorySql;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.ProfileHistory.*;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import static com.dbcompanion.common.db.ProfileHistorySql.*;

@Repository
public class ProfileHistoryRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final AiHistoryRepository common;
    public ProfileHistoryRepository(JdbcTemplate jdbc, JsonMapper json, AiHistoryRepository common) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(10); this.json = json; this.common=common;
    }
    public boolean archiveReady(String schema){return common.ready(schema);}
    public void installArchive(String schema){common.install(schema);}
    public void requireArchive(String schema){common.require(schema);}
    public String beforeEdit(String schema,String name,String id,String attribute,String actor,Map<String,Object> data) {
        return common.before(schema,"PROFILE",name,id,attribute,actor,json.writeValueAsString(data));
    }
    public void afterEdit(String schema,String name,String key,String outcome,Map<String,Object> data) {
        common.after(schema,"PROFILE",name,key,outcome,json.writeValueAsString(data));
    }
    public void execute(String sql) { jdbc.execute(sql); }
    private static MetadataEditException conflict(String text) { return new MetadataEditException(409, "Profile history asset mismatch", text); }
    public boolean canManage() {
        return !jdbc.queryForList("SELECT PRIVILEGE FROM SESSION_PRIVS WHERE PRIVILEGE='AUDIT SYSTEM'", String.class).isEmpty()
                || !jdbc.queryForList("SELECT ROLE FROM SESSION_ROLES WHERE ROLE='AUDIT_ADMIN'", String.class).isEmpty();
    }
    public String packageOwner(String user) {
        var owners = jdbc.queryForList("""
                SELECT s.TABLE_OWNER FROM SYS.ALL_SYNONYMS s JOIN SYS.ALL_OBJECTS o
                  ON o.OWNER=s.TABLE_OWNER AND o.OBJECT_NAME=s.TABLE_NAME
                WHERE s.SYNONYM_NAME='DBMS_CLOUD_AI' AND s.OWNER IN (?, 'PUBLIC') AND s.DB_LINK IS NULL
                  AND s.TABLE_NAME='DBMS_CLOUD_AI' AND o.OBJECT_TYPE='PACKAGE'
                  AND o.STATUS='VALID' AND o.ORACLE_MAINTAINED='Y'
                ORDER BY CASE WHEN s.OWNER=? THEN 0 ELSE 1 END
                """, String.class, user, user);
        if (owners.isEmpty()) throw conflict(UiMessages.text("ui.4e7071fba574", "Oracle DBMS_CLOUD_AI 패키지를 확인하지 못했습니다."));
        return owners.getFirst();
    }
    public void auditReadable() {
        jdbc.queryForList("SELECT DBID, INSTANCE_ID, SESSIONID, ENTRY_ID, STATEMENT_ID, EXECUTION_ID, SCN, EVENT_TIMESTAMP_UTC, DBUSERNAME, SQL_TEXT, SQL_BINDS, UNIFIED_AUDIT_POLICIES FROM UNIFIED_AUDIT_TRAIL WHERE 1=0");
    }
    public boolean table(String schema, String name) {
        var objects = jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=?", String.class, schema, name);
        if (objects.isEmpty()) return false;
        if (!objects.equals(List.of("TABLE"))) throw conflict(name + UiMessages.text("ui.ceef82334654", " · 동명 객체가 테이블이 아닙니다. 자동 교체하지 않습니다."));
        var comments = jdbc.queryForList("SELECT COMMENTS FROM SYS.ALL_TAB_COMMENTS WHERE OWNER=? AND TABLE_NAME=? AND TABLE_TYPE='TABLE'", String.class, schema, name);
        if (comments.size() != 1 || !MARKER.equals(comments.getFirst())) throw conflict(name + UiMessages.text("ui.a1feb492514b", " · 앱 버전 마커가 다릅니다. 자동 변경하지 않습니다."));
        var actual = jdbc.query("SELECT COLUMN_NAME, DATA_TYPE, NULLABLE FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",
                (r, n) -> r.getString(1) + ":" + r.getString(2) + ":" + r.getString(3), schema, name);
        var expected = name.equals(CONFIG) ? List.of("ID:NUMBER:N", "VERSION:NUMBER:N", "PACKAGE_OWNER:VARCHAR2:N", "CAPTURE_FROM:TIMESTAMP(6):N")
                : List.of("SEQ:NUMBER:N", "SOURCE_KEY:VARCHAR2:N", "ITEM_NO:NUMBER:N", "PROFILE_NAME:VARCHAR2:Y", "EVENT_AT:VARCHAR2:N", "ACTOR:VARCHAR2:N", "KIND:VARCHAR2:N", "PAYLOAD:CLOB:N", "RECORDED_AT:TIMESTAMP(6) WITH TIME ZONE:N");
        if (!actual.equals(expected)) throw conflict(name + UiMessages.text("ui.4a51aae78125", " · 테이블 구조가 앱 버전과 다릅니다."));
        var keys = jdbc.query("""
                SELECT c.CONSTRAINT_TYPE, LISTAGG(k.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY k.POSITION) COLUMNS_LIST
                FROM SYS.ALL_CONSTRAINTS c JOIN SYS.ALL_CONS_COLUMNS k
                  ON k.OWNER=c.OWNER AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME AND k.TABLE_NAME=c.TABLE_NAME
                WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE IN ('P','U')
                  AND c.STATUS='ENABLED' AND c.VALIDATED='VALIDATED'
                GROUP BY c.CONSTRAINT_TYPE, c.CONSTRAINT_NAME
                """, (r, n) -> r.getString(1) + ":" + r.getString(2), schema, name);
        if (!new HashSet<>(keys).equals(name.equals(CONFIG) ? Set.of("P:ID") : Set.of("P:SEQ", "U:SOURCE_KEY,ITEM_NO")))
            throw conflict(name + UiMessages.text("ui.5ee3f2e47599", " · 키/중복 방지 제약조건이 앱 버전과 다릅니다."));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME=? AND STATUS='ENABLED'", Long.class, schema, name) != 0)
            throw conflict(name + UiMessages.text("ui.e8187767b3ff", " · 알 수 없는 활성 트리거가 있습니다."));
        return true;
    }
    public void markTable(String schema, String name) { execute("COMMENT ON TABLE " + object(schema, name) + " IS '" + MARKER + "'"); }
    public boolean configured(String schema, String owner) {
        return archiveReady(schema)&&auditConfigured(schema,owner);
    }
    public boolean auditConfigured(String schema,String owner) {
        if (!table(schema, CONFIG)) return false;
        var rows = jdbc.queryForList("SELECT ID, VERSION, PACKAGE_OWNER FROM " + object(schema, CONFIG));
        if (rows.isEmpty()) return false;
        if (rows.size() != 1 || !"1".equals(rows.getFirst().get("ID").toString()) || !"1".equals(rows.getFirst().get("VERSION").toString())
                || !owner.equals(rows.getFirst().get("PACKAGE_OWNER"))) throw conflict(UiMessages.text("ui.2b1e24a23b01", "프로필 이력 설치 정보가 현재 앱/DB 패키지와 다릅니다."));
        return true;
    }
    public void configure(String schema, String owner) {
        jdbc.update("INSERT INTO " + object(schema, CONFIG) + " (ID, VERSION, PACKAGE_OWNER) SELECT 1,1,? FROM SYS.DUAL WHERE NOT EXISTS (SELECT 1 FROM " + object(schema, CONFIG) + ")", owner);
    }
    public void lock(String schema) {
        jdbc.queryForObject("SELECT ID FROM " + object(schema, CONFIG) + " WHERE ID=1 FOR UPDATE NOWAIT", Long.class);
    }
    public boolean policyExists(String schema, String owner) {
        var rows = jdbc.queryForList("""
                SELECT AUDIT_OPTION, OBJECT_SCHEMA, OBJECT_NAME, AUDIT_CONDITION, COMMON, INHERITED,
                  AUDIT_ONLY_TOPLEVEL, ORACLE_SUPPLIED, OBJECT_TYPE
                FROM SYS.AUDIT_UNIFIED_POLICIES WHERE POLICY_NAME=?
                """, policy(schema));
        if (rows.isEmpty()) return false;
        var r = rows.getFirst(); Object condition = r.get("AUDIT_CONDITION");
        if (rows.size() != 1 || !"EXECUTE".equals(r.get("AUDIT_OPTION")) || !owner.equals(r.get("OBJECT_SCHEMA"))
                || !"DBMS_CLOUD_AI".equals(r.get("OBJECT_NAME")) || (condition != null && !"NONE".equals(condition.toString()))
                || !"NO".equals(r.get("COMMON")) || !"NO".equals(r.get("INHERITED"))
                || !"NO".equals(r.get("AUDIT_ONLY_TOPLEVEL")) || !"NO".equals(r.get("ORACLE_SUPPLIED")) || !"PACKAGE".equals(r.get("OBJECT_TYPE")))
            throw conflict(policy(schema) + UiMessages.text("ui.5a362d03721a", " · 정책 정의가 다릅니다. 기존 정책을 변경하지 않습니다."));
        return true;
    }
    public boolean enabled(String schema) {
        var rows = jdbc.queryForList("SELECT ENTITY_NAME, ENTITY_TYPE, ENABLED_OPTION, SUCCESS, FAILURE FROM SYS.AUDIT_UNIFIED_ENABLED_POLICIES WHERE POLICY_NAME=?", policy(schema));
        if (rows.isEmpty()) return false;
        var r = rows.getFirst();
        if (rows.size() != 1 || !schema.equals(r.get("ENTITY_NAME")) || !"USER".equals(r.get("ENTITY_TYPE"))
                || !"BY USER".equals(r.get("ENABLED_OPTION")) || !"YES".equals(r.get("SUCCESS")) || !"YES".equals(r.get("FAILURE")))
            throw conflict(policy(schema) + UiMessages.text("ui.71cbfe828fab", " · 활성 계정/범위가 다릅니다. 기존 설정을 변경하지 않습니다."));
        return true;
    }
    public record Audit(String key, String eventAt, String actor, String sql, String binds, int returnCode, String client, boolean truncated) {}
    public List<Audit> pending(String schema, String owner) {
        String sql = "SELECT " + SOURCE_KEY + " SOURCE_KEY, TO_CHAR(a.EVENT_TIMESTAMP_UTC,'YYYY-MM-DD\"T\"HH24:MI:SS.FF6\"Z\"') EVENT_AT, "
                + "a.DBUSERNAME, a.SQL_TEXT, a.SQL_BINDS, a.RETURN_CODE, a.CLIENT_PROGRAM_NAME FROM UNIFIED_AUDIT_TRAIL a "
                + "WHERE a.DBUSERNAME=? AND a.OBJECT_SCHEMA=? AND a.OBJECT_NAME='DBMS_CLOUD_AI' AND a.ACTION_NAME='EXECUTE' "
                + "AND a.EVENT_TIMESTAMP_UTC >= (SELECT CAPTURE_FROM FROM " + object(schema, CONFIG) + " WHERE ID=1) "
                + "AND INSTR(','||REPLACE(a.UNIFIED_AUDIT_POLICIES,' ','')||',', ','||?||',')>0 "
                + "AND NOT EXISTS (SELECT 1 FROM " + AiHistorySql.table(schema) + " h WHERE h.OBJECT_TYPE='PROFILE' AND h.SOURCE_KEY=" + SOURCE_KEY + ") "
                + "ORDER BY a.EVENT_TIMESTAMP_UTC, a.INSTANCE_ID, a.SESSIONID, a.ENTRY_ID FETCH FIRST 101 ROWS ONLY";
        return jdbc.query(sql, (r, n) -> {
            String text = bounded(r, "SQL_TEXT"), binds = bounded(r, "SQL_BINDS");
            boolean truncated = (text != null && text.length() > 262144) || (binds != null && binds.length() > 262144);
            return new Audit(r.getString("SOURCE_KEY"), r.getString("EVENT_AT"), r.getString("DBUSERNAME"), clip(text), clip(binds), r.getInt("RETURN_CODE"), r.getString("CLIENT_PROGRAM_NAME"), truncated);
        }, schema, owner, policy(schema));
    }
    private static String clip(String value) { return value != null && value.length() > 262144 ? value.substring(0, 262144) : value; }
    private static String bounded(ResultSet result, String column) throws SQLException {
        try (Reader reader = result.getCharacterStream(column)) {
            if (reader == null) return null;
            var text = new StringBuilder(); char[] buffer = new char[4096]; int count;
            while (text.length() <= 262144 && (count = reader.read(buffer, 0, Math.min(buffer.length, 262145 - text.length()))) > 0) text.append(buffer, 0, count);
            return text.toString();
        } catch (IOException ex) { throw new SQLException("Cannot read audit CLOB", ex); }
    }
    public void insert(String schema, String sourceKey, int item, String profile, String at, String actor, String kind, Object data) {
        String payload = json.writeValueAsString(data);
        if(!Set.of("SNAPSHOT","REQUEST","UNCLASSIFIED").contains(kind))throw new IllegalArgumentException("Invalid profile history kind");
        String column=kind.equals("SNAPSHOT")?"AFTER_JSON":"PAYLOAD_JSON";
        jdbc.update("INSERT INTO " + AiHistorySql.table(schema) + " (OBJECT_TYPE,SOURCE_KEY, ITEM_NO, OBJECT_NAME, EVENT_AT, ACTOR, ENTRY_KIND,"+column+") VALUES ('PROFILE',?,?,?,?,?,?,?)", statement -> {
            statement.setString(1, sourceKey); statement.setInt(2, item); statement.setString(3, profile); statement.setString(4, at);
            statement.setString(5, actor); statement.setString(6, kind); statement.setClob(7, new StringReader(payload), payload.length());
        });
    }
    public Page page(String schema, String profile, String scope, int page) {
        if(archiveReady(schema))return commonPage(schema,profile,scope,page);
        if(!table(schema,HISTORY))return new Page(List.of(),page,false);
        String filter = " WHERE 1=1"; var args = new ArrayList<Object>();
        if ("unclassified".equals(scope)) filter += " AND PROFILE_NAME IS NULL";
        else if (profile != null) { filter += " AND PROFILE_NAME=?"; args.add(profile); }
        args.add((page - 1) * 10);
        var rows = jdbc.query("SELECT SEQ, PROFILE_NAME, EVENT_AT, ACTOR, KIND, PAYLOAD FROM " + object(schema, HISTORY) + filter
                + " ORDER BY SEQ DESC OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY", (r, n) -> new Entry(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),"LEGACY"), args.toArray());
        return new Page(rows.stream().limit(10).toList(), page, rows.size() > 10);
    }
    private Page commonPage(String schema,String profile,String scope,int page) {
        String filter=" WHERE OBJECT_TYPE='PROFILE'";var args=new ArrayList<Object>();
        if("unclassified".equals(scope))filter+=" AND OBJECT_NAME IS NULL";
        else if(profile!=null){filter+=" AND OBJECT_NAME=?";args.add(profile);}
        args.add((page-1)*10);
        var rows=jdbc.query("SELECT SEQ,OBJECT_NAME,EVENT_AT,ACTOR,ENTRY_KIND,BEFORE_JSON,AFTER_JSON,PAYLOAD_JSON,OUTCOME,ATTRIBUTE_NAME FROM "+AiHistorySql.table(schema)+filter
                +" ORDER BY SEQ DESC OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY",(r,n)->{
            String kind=r.getString(5),payload;
            if("EDIT".equals(kind)) {
                var data=new LinkedHashMap<String,Object>();
                data.put("before",json.readTree(r.getString(6)));String after=r.getString(7);
                data.put("after",after==null?null:json.readTree(after));data.put("outcome",r.getString(9));data.put("attribute",r.getString(10));
                payload=json.writeValueAsString(data);
            } else payload=r.getString("SNAPSHOT".equals(kind)?7:8);
            return new Entry(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),kind,payload);
        },args.toArray());
        return new Page(rows.stream().limit(10).toList(),page,rows.size()>10);
    }
    public boolean snapshot(String schema, String profile, String actor, boolean own) {
        return persistSnapshot(schema, profile, actor, currentSnapshot(schema, profile, own));
    }
    public Map<String,Object> currentSnapshot(String schema, String profile, boolean own) {
        String profiles = own ? "USER_CLOUD_AI_PROFILES" : "DBA_CLOUD_AI_PROFILES", attrs = own ? "USER_CLOUD_AI_PROFILE_ATTRIBUTES" : "DBA_CLOUD_AI_PROFILE_ATTRIBUTES";
        String sql = "SELECT p.PROFILE_ID, p.PROFILE_NAME, p.STATUS, p.DESCRIPTION, TO_CHAR(p.CREATED,'YYYY-MM-DD HH24:MI:SS.FF TZH:TZM') CREATED, "
                + "TO_CHAR(p.LAST_MODIFIED,'YYYY-MM-DD HH24:MI:SS.FF TZH:TZM') MODIFIED, a.ATTRIBUTE_NAME, a.ATTRIBUTE_VALUE "
                + "FROM " + profiles + " p LEFT JOIN " + attrs + " a ON a.PROFILE_NAME=p.PROFILE_NAME " + (own ? "" : "AND a.OWNER=p.OWNER ")
                + "WHERE p.PROFILE_NAME=? " + (own ? "" : "AND p.OWNER=? ") + "ORDER BY a.ATTRIBUTE_NAME";
        var rows = jdbc.query(sql, (r, n) -> {
            var row = new LinkedHashMap<String, String>();
            for (int i = 1; i <= 8; i++) row.put(r.getMetaData().getColumnLabel(i), r.getString(i));
            return row;
        }, own ? new Object[]{profile} : new Object[]{profile, schema});
        var data = new LinkedHashMap<String, Object>(); data.put("exists", !rows.isEmpty());
        if (!rows.isEmpty()) {
            var fields = new LinkedHashMap<>(rows.getFirst()); fields.remove("ATTRIBUTE_NAME"); fields.remove("ATTRIBUTE_VALUE"); data.put("profile", fields);
            var attributes = new TreeMap<String, String>(); for (var row : rows) if (row.get("ATTRIBUTE_NAME") != null) attributes.put(row.get("ATTRIBUTE_NAME"), row.get("ATTRIBUTE_VALUE"));
            data.put("attributes", attributes);
        }
        return data;
    }
    public boolean persistSnapshot(String schema, String profile, String actor, Map<String,Object> data) {
        String payload = json.writeValueAsString(data);
        var last = jdbc.queryForList("SELECT AFTER_JSON FROM " + AiHistorySql.table(schema) + " WHERE OBJECT_TYPE='PROFILE' AND OBJECT_NAME=? AND ENTRY_KIND IN ('SNAPSHOT','EDIT') AND AFTER_JSON IS NOT NULL ORDER BY SEQ DESC FETCH FIRST 1 ROW ONLY", String.class, profile);
        if (!last.isEmpty() && payload.equals(last.getFirst())) return false;
        String observed = jdbc.queryForObject("SELECT TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP),'YYYY-MM-DD\"T\"HH24:MI:SS.FF6\"Z\"') FROM SYS.DUAL", String.class);
        insert(schema, UUID.randomUUID().toString(), 0, profile, observed, actor, "SNAPSHOT", data); return true;
    }
}
