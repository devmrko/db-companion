package com.dbcompanion.repository;

import com.dbcompanion.model.AiSqlHistory.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiSqlHistoryRepository {
    private final JdbcTemplate jdbc;
    public AiSqlHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(10);
    }
    public record Statement(String sql, List<Object> args) {}
    private static final String AI = "'^[[:space:]]*select[[:space:]]+ai([[:space:]]|$)'";
    private static String ai(String column) { return "REGEXP_LIKE(" + column + ", " + AI + ", 'i')"; }
    // Bound instead of embedded: the history query must not find its own search expression.
    public static final String GENERATE_PATTERN = "(^|[^[:alnum:]_$#])\"?DBMS_CLOUD_AI\"?[[:space:]]*[.][[:space:]]*\"?GENERATE\"?[[:space:]]*[(]";
    private static String generate(String column, List<Object> args) {
        args.add(GENERATE_PATTERN);
        return "REGEXP_LIKE(" + column + ", ?, 'i')";
    }
    private static String match(Query q, String column, List<Object> args) {
        return switch (q.match()) {
            case select_ai -> ai(column);
            case generate -> generate(column, args);
            case all -> "(" + ai(column) + " OR " + generate(column, args)
                    + (q.source() == Source.audit ? " OR a.OBJECT_NAME='DBMS_CLOUD_AI'" : "") + ")";
        };
    }
    private static String kind(Query q, String column, List<Object> args) {
        return switch (q.match()) {
            case select_ai -> "'SELECT AI'";
            case generate -> "'DBMS_CLOUD_AI.GENERATE'";
            case all -> "CASE WHEN " + ai(column) + " THEN 'SELECT AI' WHEN " + generate(column, args)
                    + " THEN 'DBMS_CLOUD_AI.GENERATE' ELSE 'DBMS_CLOUD_AI' END";
        };
    }
    private static String date(String column) { return "TO_CHAR(" + column + ", 'YYYY-MM-DD HH24:MI:SS')"; }
    private static String interval(String column) { return column + " >= TO_TIMESTAMP(?, 'YYYY-MM-DD') AND " + column + " < TO_TIMESTAMP(?, 'YYYY-MM-DD')"; }
    private static ArrayList<Object> dates(Query q) { return new ArrayList<>(List.of(q.from().toString(), q.to().plusDays(1).toString())); }
    private static String filters(Query q, String id, String text, String actor, List<Object> args) {
        String where = "";
        if (!q.sqlId().isEmpty()) { where += " AND " + id + " = ?"; args.add(q.sqlId()); }
        if (!q.text().isEmpty()) { where += " AND INSTR(UPPER(" + text + "), UPPER(?)) > 0"; args.add(q.text()); }
        if (!q.actor().isEmpty()) { where += " AND " + actor + " = ?"; args.add(q.actor()); }
        return where;
    }
    /** Resolve only the public, local Oracle audit view; never a selected-schema shadow. */
    public String auditView() {
        var owners = jdbc.queryForList("SELECT TABLE_OWNER FROM SYS.ALL_SYNONYMS WHERE OWNER='PUBLIC' AND SYNONYM_NAME='UNIFIED_AUDIT_TRAIL' AND TABLE_NAME='UNIFIED_AUDIT_TRAIL' AND TABLE_OWNER IN ('SYS','AUDSYS') AND DB_LINK IS NULL", String.class);
        if (owners.size() != 1) throw new SourceUnavailable("Oracle PUBLIC.UNIFIED_AUDIT_TRAIL synonym could not be resolved (SYS/AUDSYS, local view)");
        return owners.getFirst() + ".UNIFIED_AUDIT_TRAIL";
    }
    private static String safeAuditView(String view) {
        if (!List.of("SYS.UNIFIED_AUDIT_TRAIL", "AUDSYS.UNIFIED_AUDIT_TRAIL").contains(view)) throw new IllegalArgumentException("Invalid audit view");
        return view;
    }
    public static Statement listStatement(Query q, String auditView) {
        var args = new ArrayList<Object>(); String sql;
        if (q.source() == Source.cache) {
            String kind = kind(q, "s.SQL_FULLTEXT", args); args.addAll(dates(q));
            sql = "SELECT s.SQL_ID K1, TO_CHAR(s.CHILD_NUMBER) K2, TO_CHAR(s.CON_ID) K3, RAWTOHEX(s.CHILD_ADDRESS) K4, s.FIRST_LOAD_TIME K5, "
                    + date("s.LAST_ACTIVE_TIME") + " OBSERVED, s.SQL_ID SQL_IDENT, s.PARSING_SCHEMA_NAME ACTOR, " + kind + " KIND, TO_CHAR(s.EXECUTIONS) TOTAL, SUBSTR(s.SQL_TEXT,1,500) PREVIEW "
                    + "FROM SYS.V_$SQL s WHERE " + interval("s.LAST_ACTIVE_TIME") + " AND " + match(q, "s.SQL_FULLTEXT", args)
                    + filters(q, "s.SQL_ID", "s.SQL_FULLTEXT", "s.PARSING_SCHEMA_NAME", args)
                    + " ORDER BY s.LAST_ACTIVE_TIME DESC, s.SQL_ID, s.CON_ID, s.CHILD_NUMBER, s.CHILD_ADDRESS";
        } else if (q.source() == Source.awr) {
            args.addAll(dates(q)); String kind = kind(q, "t.SQL_TEXT", args);
            // Statistics are grouped per database/container/text, never joined on SQL_ID alone.
            sql = "WITH h AS (SELECT st.DBID, st.SQL_ID, st.CON_DBID, st.CON_ID, MIN(sn.BEGIN_INTERVAL_TIME) FIRST_SEEN, MAX(sn.END_INTERVAL_TIME) LAST_SEEN, SUM(st.EXECUTIONS_DELTA) TOTAL "
                    + "FROM SYS.DBA_HIST_SQLSTAT st JOIN SYS.DBA_HIST_SNAPSHOT sn ON sn.DBID=st.DBID AND sn.INSTANCE_NUMBER=st.INSTANCE_NUMBER AND sn.SNAP_ID=st.SNAP_ID "
                    + "WHERE " + interval("sn.END_INTERVAL_TIME")
                    + " GROUP BY st.DBID, st.SQL_ID, st.CON_DBID, st.CON_ID) "
                    + "SELECT TO_CHAR(t.DBID) K1, t.SQL_ID K2, NVL(TO_CHAR(t.CON_DBID),'-') K3, NVL(TO_CHAR(t.CON_ID),'-') K4, "
                    + date("h.FIRST_SEEN") + " || ' – ' || " + date("h.LAST_SEEN") + " OBSERVED, t.SQL_ID SQL_IDENT, NULL ACTOR, " + kind + " KIND, TO_CHAR(h.TOTAL) TOTAL, DBMS_LOB.SUBSTR(t.SQL_TEXT,500,1) PREVIEW "
                    + "FROM SYS.DBA_HIST_SQLTEXT t JOIN h ON h.DBID=t.DBID AND h.SQL_ID=t.SQL_ID AND NVL(h.CON_DBID,-1)=NVL(t.CON_DBID,-1) AND NVL(h.CON_ID,-1)=NVL(t.CON_ID,-1) "
                    + "WHERE " + match(q, "t.SQL_TEXT", args) + filters(q, "t.SQL_ID", "t.SQL_TEXT", "", args)
                    + " ORDER BY h.LAST_SEEN DESC, t.DBID, t.SQL_ID, t.CON_DBID, t.CON_ID";
        } else {
            String kind = kind(q, "a.SQL_TEXT", args); args.addAll(dates(q));
            sql = "SELECT NVL(TO_CHAR(a.DBID),'-') K1, NVL(TO_CHAR(a.INSTANCE_ID),'-') K2, NVL(TO_CHAR(a.SESSIONID),'-') K3, NVL(TO_CHAR(a.ENTRY_ID),'-') K4, NVL(TO_CHAR(a.STATEMENT_ID),'-') K5, "
                    + "TO_CHAR(a.EVENT_TIMESTAMP_UTC,'YYYY-MM-DD HH24:MI:SS.FF6') K6, NVL(a.EXECUTION_ID,'-') K7, NVL(TO_CHAR(a.SCN),'-') K8, "
                    + date("a.EVENT_TIMESTAMP_UTC") + " OBSERVED, NULL SQL_IDENT, a.DBUSERNAME ACTOR, " + kind + " KIND, TO_CHAR(a.RETURN_CODE) TOTAL, DBMS_LOB.SUBSTR(a.SQL_TEXT,500,1) PREVIEW "
                    + "FROM " + safeAuditView(auditView) + " a WHERE " + interval("a.EVENT_TIMESTAMP_UTC") + " AND " + match(q, "a.SQL_TEXT", args)
                    + filters(q, "", "a.SQL_TEXT", "a.DBUSERNAME", args)
                    + " ORDER BY a.EVENT_TIMESTAMP_UTC DESC, a.DBID, a.INSTANCE_ID, a.SESSIONID, a.ENTRY_ID, a.STATEMENT_ID, a.EXECUTION_ID, a.SCN";
        }
        args.add(q.offset());
        return new Statement(sql + " OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY", List.copyOf(args));
    }
    public Page page(Query query) {
        var statement = listStatement(query, query.source() == Source.audit ? auditView() : "");
        int keys = switch (query.source()) { case cache -> 5; case awr -> 4; case audit -> 8; };
        return Page.of(jdbc.query(statement.sql(), (r, i) -> {
            var key = new ArrayList<String>(); for (int k = 1; k <= keys; k++) key.add(r.getString("K" + k));
            return new Item(UUID.randomUUID().toString(), r.getString("OBSERVED"), r.getString("SQL_IDENT"), r.getString("ACTOR"),
                    r.getString("KIND"), r.getString("TOTAL"), r.getString("PREVIEW"), key);
        }, statement.args().toArray()), query.page());
    }
    public static Statement detailStatement(Selection selected, String auditView) {
        var key = selected.item().keys(); var args = new ArrayList<Object>(key); String sql;
        switch (selected.query().source()) {
            case cache -> sql = "SELECT s.SQL_ID, s.CHILD_NUMBER, s.CON_ID, s.PARSING_SCHEMA_NAME, s.FIRST_LOAD_TIME, " + date("s.LAST_ACTIVE_TIME") + " LAST_ACTIVE_TIME, s.EXECUTIONS, s.SQL_FULLTEXT FULL_SQL "
                    + "FROM SYS.V_$SQL s WHERE s.SQL_ID=? AND s.CHILD_NUMBER=TO_NUMBER(?) AND s.CON_ID=TO_NUMBER(?) AND RAWTOHEX(s.CHILD_ADDRESS)=? AND s.FIRST_LOAD_TIME=? AND " + match(selected.query(), "s.SQL_FULLTEXT", args);
            case awr -> sql = "SELECT t.DBID, t.SQL_ID, t.CON_DBID, t.CON_ID, t.SQL_TEXT FULL_SQL FROM SYS.DBA_HIST_SQLTEXT t "
                    + "WHERE t.DBID=TO_NUMBER(?) AND t.SQL_ID=? AND NVL(TO_CHAR(t.CON_DBID),'-')=? AND NVL(TO_CHAR(t.CON_ID),'-')=? AND " + match(selected.query(), "t.SQL_TEXT", args);
            case audit -> sql = "SELECT a.DBID, a.INSTANCE_ID, a.SESSIONID, a.ENTRY_ID, a.STATEMENT_ID, a.EXECUTION_ID, a.SCN, "
                    + "TO_CHAR(a.EVENT_TIMESTAMP_UTC,'YYYY-MM-DD HH24:MI:SS.FF6') EVENT_TIMESTAMP_UTC, a.DBUSERNAME, a.AUDIT_TYPE, a.ACTION_NAME, a.RETURN_CODE, a.OBJECT_SCHEMA, a.OBJECT_NAME, a.CLIENT_PROGRAM_NAME, a.UNIFIED_AUDIT_POLICIES, a.SQL_TEXT FULL_SQL "
                    + "FROM " + safeAuditView(auditView) + " a WHERE NVL(TO_CHAR(a.DBID),'-')=? AND NVL(TO_CHAR(a.INSTANCE_ID),'-')=? AND NVL(TO_CHAR(a.SESSIONID),'-')=? AND NVL(TO_CHAR(a.ENTRY_ID),'-')=? AND NVL(TO_CHAR(a.STATEMENT_ID),'-')=? "
                    + "AND a.EVENT_TIMESTAMP_UTC=TO_TIMESTAMP(?,'YYYY-MM-DD HH24:MI:SS.FF6') AND NVL(a.EXECUTION_ID,'-')=? AND NVL(TO_CHAR(a.SCN),'-')=? AND " + match(selected.query(), "a.SQL_TEXT", args);
            default -> throw new IllegalArgumentException("Invalid source");
        }
        return new Statement(sql + " FETCH FIRST 2 ROWS ONLY", List.copyOf(args));
    }
    public Detail detail(Selection selected) {
        var statement = detailStatement(selected, selected.query().source() == Source.audit ? auditView() : "");
        var rows = jdbc.query(statement.sql(), (r, i) -> new Detail(fields(r, "FULL_SQL"), r.getString("FULL_SQL")), statement.args().toArray());
        if (rows.size() > 1) throw new AmbiguousRecord();
        if (rows.isEmpty()) return null;
        var detail = rows.getFirst();
        if (selected.query().source() != Source.awr) return detail;
        var fields = new ArrayList<>(detail.fields());
        fields.add(new Field("SNAPSHOT_INTERVAL_DB", selected.item().time()));
        fields.add(new Field("EXECUTIONS_DELTA_SUM", selected.item().count()));
        return new Detail(List.copyOf(fields), detail.sql());
    }
    public static final String POLICY_SQL = """
            SELECT p.POLICY_NAME, p.OBJECT_SCHEMA, p.OBJECT_NAME, p.AUDIT_OPTION, p.AUDIT_CONDITION,
              p.CONDITION_EVAL_OPT, p.AUDIT_ONLY_TOPLEVEL, e.ENABLED_OPTION, e.ENTITY_NAME, e.ENTITY_TYPE, e.SUCCESS, e.FAILURE
            FROM SYS.AUDIT_UNIFIED_POLICIES p LEFT JOIN SYS.AUDIT_UNIFIED_ENABLED_POLICIES e ON e.POLICY_NAME=p.POLICY_NAME
            WHERE p.OBJECT_NAME='DBMS_CLOUD_AI' AND p.OBJECT_TYPE='PACKAGE'
            ORDER BY p.POLICY_NAME, p.OBJECT_SCHEMA, e.ENTITY_NAME, e.ENTITY_TYPE, e.ENABLED_OPTION
            FETCH FIRST 201 ROWS ONLY
            """;
    public Policies policies() {
        var rows = jdbc.query(POLICY_SQL, (r, i) -> fields(r, ""));
        return new Policies(rows.subList(0, Math.min(200, rows.size())), rows.size() > 200, Instant.now(), null);
    }
    private static List<Field> fields(ResultSet r, String exclude) throws SQLException {
        var fields = new ArrayList<Field>(); var meta = r.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) if (!meta.getColumnLabel(i).equals(exclude))
            fields.add(new Field(meta.getColumnLabel(i), r.getString(i)));
        return List.copyOf(fields);
    }
}
