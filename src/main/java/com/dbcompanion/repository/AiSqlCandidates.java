package com.dbcompanion.repository;

import com.dbcompanion.model.AiSqlHistory.*;
import com.dbcompanion.model.SelectAiTest.Action;
import com.dbcompanion.service.SelectAiSqlReferences;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Display-only correlation: never a proven GENERATE return, execution or privileged login. */
public final class AiSqlCandidates {
    private final JdbcTemplate jdbc;
    public AiSqlCandidates(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Anchor(String sqlId, String container, String instance, String schema, String time) {}
    public record Row(String sqlId, String sql, String time, long offset, String executions, String elapsed) {}
    public record Evidence(String status, Map<String,String> matches) {
        public Evidence { matches = Map.copyOf(matches); }
    }
    public static AiSqlHistoryRepository.Statement anchorStatement(Selection selected) {
        if (selected.query().source() != Source.cache) throw new IllegalArgumentException("Shared SQL required");
        var args = new ArrayList<Object>(selected.item().keys()); args.add(selected.item().time());
        return new AiSqlHistoryRepository.Statement("SELECT s.SQL_ID,TO_CHAR(s.CON_ID),SYS_CONTEXT('USERENV','INSTANCE'),s.PARSING_SCHEMA_NAME,TO_CHAR(s.LAST_ACTIVE_TIME,'YYYY-MM-DD HH24:MI:SS') "
                + "FROM SYS.V_$SQL s WHERE s.SQL_ID=? AND s.CHILD_NUMBER=TO_NUMBER(?) AND s.CON_ID=TO_NUMBER(?) AND RAWTOHEX(s.CHILD_ADDRESS)=? AND s.FIRST_LOAD_TIME=? "
                + "AND TO_CHAR(s.LAST_ACTIVE_TIME,'YYYY-MM-DD HH24:MI:SS')=? FETCH FIRST 2 ROWS ONLY", List.copyOf(args));
    }
    public static AiSqlHistoryRepository.Statement nearbyStatement(Anchor a) {
        return new AiSqlHistoryRepository.Statement("SELECT s.SQL_ID,s.SQL_FULLTEXT,TO_CHAR(s.LAST_ACTIVE_TIME,'YYYY-MM-DD HH24:MI:SS'), "
                + "ROUND((s.LAST_ACTIVE_TIME-TO_DATE(?,'YYYY-MM-DD HH24:MI:SS'))*86400),TO_CHAR(s.EXECUTIONS),TO_CHAR(ROUND(s.ELAPSED_TIME/1000000,6)) "
                + "FROM SYS.V_$SQL s WHERE s.CON_ID=TO_NUMBER(?) AND s.PARSING_SCHEMA_NAME=? AND s.COMMAND_TYPE=3 AND s.SQL_ID<>? "
                + "AND s.LAST_ACTIVE_TIME BETWEEN TO_DATE(?,'YYYY-MM-DD HH24:MI:SS')-1/1440 AND TO_DATE(?,'YYYY-MM-DD HH24:MI:SS')+1/1440 "
                + "ORDER BY ABS(s.LAST_ACTIVE_TIME-TO_DATE(?,'YYYY-MM-DD HH24:MI:SS')),s.EXECUTIONS DESC,s.SQL_ID,s.CHILD_NUMBER FETCH FIRST 41 ROWS ONLY",
                List.of(a.time(), a.container(), a.schema(), a.sqlId(), a.time(), a.time(), a.time()));
    }
    public static AiSqlHistoryRepository.Statement evidenceStatement(Anchor a, List<String> ids) {
        if (ids.isEmpty() || ids.size() > 40) throw new IllegalArgumentException("Bounded candidates required");
        var args = new ArrayList<Object>(List.of(a.container(),a.instance(),a.sqlId(),a.sqlId(),a.time(),a.time(),a.container())); args.addAll(ids);
        args.add(a.time()); args.add(a.time());
        return new AiSqlHistoryRepository.Statement("WITH anchor AS (SELECT DISTINCT INST_ID,SESSION_ID,SESSION_SERIAL#,USER_ID,SQL_EXEC_ID,SQL_EXEC_START "
                + "FROM SYS.GV_$ACTIVE_SESSION_HISTORY WHERE CON_ID=TO_NUMBER(?) AND INST_ID=TO_NUMBER(?) AND TOP_LEVEL_SQL_ID=? AND SQL_ID=? "
                + "AND SQL_EXEC_START IS NOT NULL AND SAMPLE_TIME BETWEEN TO_TIMESTAMP(?,'YYYY-MM-DD HH24:MI:SS')-INTERVAL '1' MINUTE AND TO_TIMESTAMP(?,'YYYY-MM-DD HH24:MI:SS')+INTERVAL '1' MINUTE) "
                + "SELECT c.SQL_ID,MIN(CASE WHEN c.INST_ID=a.INST_ID AND c.SESSION_ID=a.SESSION_ID AND c.SESSION_SERIAL#=a.SESSION_SERIAL# THEN 0 ELSE 1 END) MATCH_KIND "
                + "FROM SYS.GV_$ACTIVE_SESSION_HISTORY c JOIN anchor a ON c.USER_ID=a.USER_ID AND "
                + "((c.INST_ID=a.INST_ID AND c.SESSION_ID=a.SESSION_ID AND c.SESSION_SERIAL#=a.SESSION_SERIAL#) OR "
                + "(c.QC_INSTANCE_ID=a.INST_ID AND c.QC_SESSION_ID=a.SESSION_ID AND c.QC_SESSION_SERIAL#=a.SESSION_SERIAL#)) "
                + "WHERE c.CON_ID=TO_NUMBER(?) AND c.SQL_ID IN (" + String.join(",", Collections.nCopies(ids.size(),"?")) + ") "
                + "AND c.SQL_EXEC_START>=a.SQL_EXEC_START AND c.SAMPLE_TIME BETWEEN TO_TIMESTAMP(?,'YYYY-MM-DD HH24:MI:SS')-INTERVAL '1' MINUTE AND TO_TIMESTAMP(?,'YYYY-MM-DD HH24:MI:SS')+INTERVAL '1' MINUTE GROUP BY c.SQL_ID", List.copyOf(args));
    }
    public Candidates find(Selection selected) {
        var statement = anchorStatement(selected);
        var anchors = jdbc.query(statement.sql(), (r,n)->new Anchor(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),statement.args().toArray());
        if (anchors.size()>1) throw new AmbiguousRecord();
        if (anchors.isEmpty()) return null;
        var anchor = anchors.getFirst(); var nearby = nearbyStatement(anchor);
        var rows = jdbc.query(nearby.sql(), (r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getString(6)),nearby.args().toArray());
        var unique = new LinkedHashMap<String,Row>();
        for (var row:rows.subList(0,Math.min(40,rows.size()))) unique.putIfAbsent(row.sqlId(),row);
        var eligible = new ArrayList<SqlCandidate>(); int omitted=0; boolean limited=rows.size()>40;
        long deadline=System.nanoTime()+2_000_000_000L;
        for (var row:unique.values()) {
            if (System.nanoTime()>deadline) { limited=true; omitted++; continue; }
            var objects=businessObjects(row.sql());
            if (objects.isEmpty()) { omitted++; continue; }
            eligible.add(new SqlCandidate(row.sqlId(),row.sql(),objects,row.time(),row.offset(),row.executions(),row.elapsed(),"TIME_SCHEMA"));
        }
        var evidence=eligible.isEmpty()?new Evidence("NOT_CHECKED",Map.of()):evidence(anchor,eligible.stream().map(SqlCandidate::sqlId).toList());
        var ranked=rank(eligible,evidence.matches());
        return new Candidates(anchor.time(),anchor.schema(),evidence.status(),ranked.subList(0,Math.min(3,ranked.size())),limited||ranked.size()>3,omitted);
    }
    /** Conservative display filter; skipped/unparseable statements are disclosed, not declared unrelated. */
    public static List<String> businessObjects(String sql) {
        var analysis=SelectAiSqlReferences.analyze(Action.SQL,sql);
        if (!analysis.status().equals("PARSED")) return List.of();
        return analysis.tables().stream().filter(t->{
            String owner=Objects.toString(t.owner(),"").toUpperCase(Locale.ROOT),name=t.name().toUpperCase(Locale.ROOT);
            return !Set.of("SYS","SYSTEM","AUDSYS","C##CLOUD$SERVICE").contains(owner) && !owner.startsWith("APEX_")
                    && !name.equals("DUAL") && !name.startsWith("USER_") && !name.startsWith("ALL_") && !name.startsWith("DBA_")
                    && !name.startsWith("V$") && !name.startsWith("GV$") && !name.startsWith("V_$") && !name.startsWith("GV_$") && !name.startsWith("DBC_");
        }).map(SelectAiSqlReferences.Table::sqlName).toList();
    }
    private Evidence evidence(Anchor anchor,List<String> ids) {
        try {
            var statement=evidenceStatement(anchor,ids); var matches=new HashMap<String,String>();
            jdbc.query(statement.sql(),r->{ matches.put(r.getString(1),r.getInt(2)==0?"SESSION":"COORDINATOR"); },statement.args().toArray());
            return new Evidence(matches.isEmpty()?"NO_MATCH":"AVAILABLE",matches);
        } catch (RuntimeException ex) {
            Throwable cause=ex; while (!(cause instanceof SQLException)&&cause.getCause()!=null) cause=cause.getCause();
            int code=cause instanceof SQLException e?e.getErrorCode():0;
            return new Evidence(code==942||code==1031?"UNAVAILABLE":"UNCONFIRMED",Map.of());
        }
    }
    public static List<SqlCandidate> rank(List<SqlCandidate> candidates,Map<String,String> evidence) {
        return candidates.stream().map(c->new SqlCandidate(c.sqlId(),c.sql(),c.objects(),c.lastActive(),c.offsetSeconds(),c.executions(),c.elapsedSeconds(),evidence.getOrDefault(c.sqlId(),"TIME_SCHEMA")))
                .sorted(Comparator.comparingInt((SqlCandidate c)->c.evidence().equals("TIME_SCHEMA")?1:0)
                        .thenComparingLong(c->Math.abs(c.offsetSeconds())).thenComparing(SqlCandidate::sqlId)).toList();
    }
}
