package com.dbcompanion.repository;

import com.dbcompanion.model.SqlCacheArchive.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SqlCacheArchiveRepository {
    public static final String TABLE = "DBC_SQL_CACHE_ARCHIVE", JOB = "DBC_SQL_CACHE_CAPTURE";
    public static final String MARKER = "DBC_SQL_ARCHIVE_V1";
    private static final String PREFIX = "/* DBC_SQL_ARCHIVE_V1 */ ";
    public static final String COLLECT = collector();
    public static final String CREATE_TABLE = """
            CREATE TABLE DBC_SQL_CACHE_ARCHIVE (
              CURSOR_KEY VARCHAR2(64) PRIMARY KEY,
              INSTANCE_ID NUMBER NOT NULL, CON_ID NUMBER NOT NULL,
              SQL_ID VARCHAR2(13) NOT NULL, CHILD_NUMBER NUMBER NOT NULL,
              CHILD_ADDRESS VARCHAR2(32), FIRST_LOAD_TIME VARCHAR2(76), LAST_LOAD_TIME VARCHAR2(76),
              PARSING_SCHEMA_NAME VARCHAR2(128) NOT NULL, SQL_FULLTEXT CLOB, SQL_PREVIEW VARCHAR2(1000),
              FIRST_SEEN TIMESTAMP WITH TIME ZONE NOT NULL, LAST_SEEN TIMESTAMP WITH TIME ZONE NOT NULL,
              LAST_ACTIVE_TIME DATE, EXECUTIONS NUMBER, ELAPSED_TIME NUMBER, CPU_TIME NUMBER,
              BUFFER_GETS NUMBER, DISK_READS NUMBER, ROWS_PROCESSED NUMBER,
              MODULE VARCHAR2(64), ACTION VARCHAR2(64), PLAN_HASH_VALUE NUMBER
            )""";
    private final JdbcTemplate jdbc;
    public SqlCacheArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(20);
    }
    private static String collector() {
        try { return new ClassPathResource("sql/sql-cache-archive-collect.sql").getContentAsString(StandardCharsets.UTF_8).strip(); }
        catch (IOException ex) { throw new IllegalStateException("Missing SQL archive collector",ex); }
    }
    public String tableState() {
        var objects=jdbc.queryForList(PREFIX+"SELECT OBJECT_TYPE FROM SYS.USER_OBJECTS WHERE OBJECT_NAME=?",String.class,TABLE);
        if(objects.isEmpty()) return "MISSING";
        if(!objects.equals(List.of("TABLE"))) return "CONFLICT";
        var comments=jdbc.queryForList(PREFIX+"SELECT COMMENTS FROM SYS.USER_TAB_COMMENTS WHERE TABLE_NAME=?",String.class,TABLE);
        if(!comments.equals(List.of(MARKER))) return "CONFLICT";
        var columns=jdbc.queryForList(PREFIX+"SELECT COLUMN_NAME || ':' || DATA_TYPE FROM SYS.USER_TAB_COLUMNS WHERE TABLE_NAME=? ORDER BY COLUMN_ID",String.class,TABLE);
        var expected=List.of("CURSOR_KEY:VARCHAR2","INSTANCE_ID:NUMBER","CON_ID:NUMBER","SQL_ID:VARCHAR2","CHILD_NUMBER:NUMBER",
                "CHILD_ADDRESS:VARCHAR2","FIRST_LOAD_TIME:VARCHAR2","LAST_LOAD_TIME:VARCHAR2","PARSING_SCHEMA_NAME:VARCHAR2",
                "SQL_FULLTEXT:CLOB","SQL_PREVIEW:VARCHAR2","FIRST_SEEN:TIMESTAMP(6) WITH TIME ZONE","LAST_SEEN:TIMESTAMP(6) WITH TIME ZONE",
                "LAST_ACTIVE_TIME:DATE","EXECUTIONS:NUMBER","ELAPSED_TIME:NUMBER","CPU_TIME:NUMBER","BUFFER_GETS:NUMBER",
                "DISK_READS:NUMBER","ROWS_PROCESSED:NUMBER","MODULE:VARCHAR2","ACTION:VARCHAR2","PLAN_HASH_VALUE:NUMBER");
        var keys=jdbc.queryForList(PREFIX+"SELECT cc.COLUMN_NAME FROM SYS.USER_CONSTRAINTS c JOIN SYS.USER_CONS_COLUMNS cc ON c.CONSTRAINT_NAME=cc.CONSTRAINT_NAME AND c.TABLE_NAME=cc.TABLE_NAME WHERE c.TABLE_NAME=? AND c.CONSTRAINT_TYPE='P' AND c.STATUS='ENABLED' ORDER BY cc.POSITION",String.class,TABLE);
        return columns.equals(expected)&&keys.equals(List.of("CURSOR_KEY"))?"READY":"CONFLICT";
    }
    public Status status(String owner) {
        String table=tableState(),access="READY";
        try { jdbc.queryForObject(PREFIX+"SELECT COUNT(*) FROM SYS.V_$SQL WHERE 1=0",Integer.class); }
        catch(RuntimeException ex) { access=CredentialCatalogRepository.error(ex); }
        var jobs=jdbc.queryForList(PREFIX+"SELECT JOB_TYPE, JOB_ACTION, ENABLED, STATE, REPEAT_INTERVAL, TO_CHAR(NEXT_RUN_DATE,'YYYY-MM-DD HH24:MI:SS TZH:TZM') NEXT_RUN, TO_CHAR(LAST_START_DATE,'YYYY-MM-DD HH24:MI:SS TZH:TZM') LAST_RUN FROM SYS.USER_SCHEDULER_JOBS WHERE JOB_NAME=?",JOB);
        String job="MISSING",state="",interval="",next="",last="",result="",error=""; boolean enabled=false;
        if(!jobs.isEmpty()) {
            var row=jobs.getFirst();
            job="PLSQL_BLOCK".equals(row.get("JOB_TYPE")) && COLLECT.equals(string(row.get("JOB_ACTION")))?"READY":"CONFLICT";
            enabled="TRUE".equals(row.get("ENABLED")); state=string(row.get("STATE")); interval=string(row.get("REPEAT_INTERVAL"));
            next=string(row.get("NEXT_RUN")); last=string(row.get("LAST_RUN"));
            var runs=jdbc.queryForList(PREFIX+"SELECT STATUS, ERROR# FROM SYS.USER_SCHEDULER_JOB_RUN_DETAILS WHERE JOB_NAME=? ORDER BY LOG_ID DESC FETCH FIRST 1 ROW ONLY",JOB);
            if(!runs.isEmpty()){result=string(runs.getFirst().get("STATUS"));error=string(runs.getFirst().get("ERROR#"));}
        }
        return new Status(owner,table,job,enabled,state,interval,next,last,result,error,access);
    }
    public static String literal(String value) { return "'"+value.replace("'","''")+"'"; }
    public static List<String> plan(Operation operation,int seconds,Status status) {
        if(operation==null)throw new IllegalArgumentException("Missing operation");
        com.dbcompanion.model.SqlCacheArchive.interval(seconds);
        if(status.table().equals("CONFLICT")||status.job().equals("CONFLICT")) throw new IllegalArgumentException("Archive object conflict");
        if(operation!=Operation.INSTALL && (!status.table().equals("READY")||!status.job().equals("READY")))
            throw new IllegalArgumentException("Archive is not installed");
        if(operation!=Operation.PAUSE && !status.sourceAccess().equals("READY")) throw new IllegalArgumentException("V$SQL read access required");
        var sql=new ArrayList<String>(); String repeat="FREQ=SECONDLY;INTERVAL="+seconds;
        switch(operation) {
            case INSTALL -> {
                if(status.job().equals("READY")) throw new IllegalArgumentException("Already installed");
                if(status.table().equals("MISSING")) {
                    sql.add(CREATE_TABLE); sql.add("COMMENT ON TABLE "+TABLE+" IS '"+MARKER+"'");
                }
                // Create disabled first: a partial installation cannot silently start collecting.
                sql.add("BEGIN DBMS_SCHEDULER.CREATE_JOB(job_name=>'"+JOB+"', job_type=>'PLSQL_BLOCK', job_action=>"+literal(COLLECT)
                        +", start_date=>SYSTIMESTAMP, repeat_interval=>"+literal(repeat)+", enabled=>FALSE, auto_drop=>FALSE, comments=>'"+MARKER+"'); END;");
                sql.add("BEGIN DBMS_SCHEDULER.SET_ATTRIBUTE('"+JOB+"','logging_level',DBMS_SCHEDULER.LOGGING_RUNS); DBMS_SCHEDULER.ENABLE('"+JOB+"'); END;");
            }
            case CONFIGURE -> sql.add("BEGIN DBMS_SCHEDULER.SET_ATTRIBUTE('"+JOB+"','repeat_interval',"+literal(repeat)+"); END;");
            case PAUSE -> sql.add("BEGIN DBMS_SCHEDULER.DISABLE('"+JOB+"', force=>TRUE); END;");
            case RESUME -> sql.add("BEGIN DBMS_SCHEDULER.ENABLE('"+JOB+"'); END;");
            // Same scheduler job, not a competing direct MERGE. It executes asynchronously.
            case RUN -> sql.add("BEGIN DBMS_SCHEDULER.RUN_JOB('"+JOB+"', use_current_session=>FALSE); END;");
            default -> throw new IllegalArgumentException("Invalid operation");
        }
        return List.copyOf(sql);
    }
    public void apply(List<String> statements) { for(var sql:statements) jdbc.execute(sql); }
    public static String helpScript() {
        var empty=new Status("<LOGIN_USER>","MISSING","MISSING",false,"","","","","","","READY");
        return "-- Administrator prerequisites (review before granting):\n"
                +"-- GRANT READ ON SYS.V_$SQL TO \"<LOGIN_USER>\";\n"
                +"-- GRANT CREATE TABLE, CREATE JOB TO \"<LOGIN_USER>\";\n"
                +"-- Connect as the archive owner. Review storage quota separately.\n\n"
                +String.join("\n\n",plan(Operation.INSTALL,60,empty).stream().map(s->s+(s.endsWith("END;")?"\n/":";")).toList())
                +"\n\n-- Verify the job and observed cursors (not per-execution history):\n"
                +"SELECT JOB_NAME, ENABLED, STATE, REPEAT_INTERVAL FROM USER_SCHEDULER_JOBS WHERE JOB_NAME='"+JOB+"';\n"
                +"SELECT SQL_ID, CHILD_NUMBER, FIRST_SEEN, LAST_SEEN, EXECUTIONS, SQL_FULLTEXT FROM "+TABLE+" ORDER BY LAST_SEEN DESC FETCH FIRST 20 ROWS ONLY;\n";
    }
    public record Statement(String sql,List<Object> args) {}
    public static Statement listSql(Query q) {
        var args=new ArrayList<Object>(List.of(q.from().toString(),q.to().plusDays(1).toString()));
        String where="LAST_SEEN >= TO_TIMESTAMP_TZ(? || ' +00:00','YYYY-MM-DD TZH:TZM') AND LAST_SEEN < TO_TIMESTAMP_TZ(? || ' +00:00','YYYY-MM-DD TZH:TZM')";
        if(!q.sqlId().isEmpty()){where+=" AND SQL_ID=?";args.add(q.sqlId());}
        if(!q.text().isEmpty()){where+=" AND INSTR(UPPER(SQL_FULLTEXT),UPPER(?))>0";args.add(q.text());}
        String ai="REGEXP_LIKE(SQL_FULLTEXT, ?, 'i')";
        if(q.match()!=Match.all) {
            String select="^[[:space:]]*select[[:space:]]+ai([[:space:]]|$)";
            if(q.match()==Match.ai){where+=" AND ("+ai+" OR "+ai+")";args.add(select);args.add(AiSqlHistoryRepository.GENERATE_PATTERN);}
            else {where+=" AND "+ai;args.add(q.match()==Match.select_ai?select:AiSqlHistoryRepository.GENERATE_PATTERN);}
        }
        args.add((q.page()-1)*20);
        return new Statement(PREFIX+"SELECT CURSOR_KEY, SQL_ID, PARSING_SCHEMA_NAME, INSTANCE_ID, CHILD_NUMBER, "
                +"TO_CHAR(FIRST_SEEN,'YYYY-MM-DD HH24:MI:SS TZH:TZM') FIRST_SEEN, TO_CHAR(LAST_SEEN,'YYYY-MM-DD HH24:MI:SS TZH:TZM') LAST_SEEN, "
                +"TO_CHAR(LAST_ACTIVE_TIME,'YYYY-MM-DD HH24:MI:SS') LAST_ACTIVE_TIME, EXECUTIONS, SQL_PREVIEW FROM "+TABLE+" WHERE "+where
                +" ORDER BY LAST_SEEN DESC, CURSOR_KEY OFFSET ? ROWS FETCH NEXT 21 ROWS ONLY",List.copyOf(args));
    }
    public Page page(Query query) {
        requireTable();var s=listSql(query);var rows=jdbc.queryForList(s.sql(),s.args().toArray());
        return new Page(rows.subList(0,Math.min(20,rows.size())),query.page(),rows.size()>20);
    }
    public Map<String,Object> detail(String key) {
        requireTable();var rows=jdbc.query(PREFIX+"SELECT SQL_ID, CHILD_NUMBER, INSTANCE_ID, CON_ID, FIRST_LOAD_TIME, LAST_LOAD_TIME, EXECUTIONS, ELAPSED_TIME, CPU_TIME, BUFFER_GETS, DISK_READS, ROWS_PROCESSED, MODULE, ACTION, PLAN_HASH_VALUE, SQL_FULLTEXT FROM "+TABLE+" WHERE CURSOR_KEY=?",
                (r,i)->{var row=new org.springframework.jdbc.core.ColumnMapRowMapper().mapRow(r,i);row.put("SQL_FULLTEXT",r.getString("SQL_FULLTEXT"));return row;},
                com.dbcompanion.model.SqlCacheArchive.key(key));
        return rows.isEmpty()?Map.of():rows.getFirst();
    }
    private void requireTable(){if(!tableState().equals("READY"))throw new IllegalArgumentException("Archive table not ready");}
    private static String string(Object value) {
        if(value==null)return "";
        if(value instanceof Clob clob)try{return clob.getSubString(1,Math.toIntExact(clob.length()));}
        catch(SQLException ex){throw new IllegalStateException("Cannot read archive CLOB",ex);}
        return value.toString();
    }
}
