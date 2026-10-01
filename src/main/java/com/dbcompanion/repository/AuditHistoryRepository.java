package com.dbcompanion.repository;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.AuditHistory.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.dbcompanion.common.db.AuditHistorySql.*;

@Repository
public class AuditHistoryRepository {
    private final JdbcTemplate jdbc;
    public AuditHistoryRepository(JdbcTemplate template){jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(45);}
    public String auditView(){
        var rows=jdbc.queryForList("SELECT TABLE_OWNER||'.'||TABLE_NAME FROM SYS.ALL_SYNONYMS WHERE OWNER='PUBLIC' AND SYNONYM_NAME='UNIFIED_AUDIT_TRAIL' AND TABLE_NAME='UNIFIED_AUDIT_TRAIL' AND TABLE_OWNER IN ('SYS','AUDSYS') AND DB_LINK IS NULL",String.class);
        if(rows.size()!=1)throw new IllegalArgumentException("Oracle audit view unavailable");return view(rows.getFirst());
    }
    private String tableState(String table,List<String> expected){
        var types=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.USER_OBJECTS WHERE OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL",String.class,table);
        if(types.isEmpty())return "MISSING";if(!types.equals(List.of("TABLE")))return "CONFLICT";
        if(!jdbc.queryForList("SELECT COMMENTS FROM SYS.USER_TAB_COMMENTS WHERE TABLE_NAME=?",String.class,table).equals(List.of(MARKER)))return "CONFLICT";
        var cols=jdbc.queryForList("SELECT COLUMN_NAME FROM SYS.USER_TAB_COLUMNS WHERE TABLE_NAME=? ORDER BY COLUMN_ID",String.class,table);
        var pk=jdbc.queryForList("SELECT cc.COLUMN_NAME FROM SYS.USER_CONSTRAINTS c JOIN SYS.USER_CONS_COLUMNS cc ON c.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE c.TABLE_NAME=? AND c.CONSTRAINT_TYPE='P' AND c.STATUS='ENABLED' ORDER BY cc.POSITION",String.class,table);
        return cols.equals(expected)&&pk.equals(List.of(expected.getFirst()))?"READY":"CONFLICT";
    }
    private String store(){
        var cols=new ArrayList<>(List.of("RECORD_KEY","SOURCE_DATABASE"));cols.addAll(FIELDS);cols.add("CAPTURED_AT");
        String a=tableState(TABLE,cols),b=tableState(CONFIG,List.of("CONFIG_ID","VERSION_TAG","INTERVAL_MINUTES","RETENTION_DAYS","LAST_SCAN_TO","LAST_SUCCESS","LAST_ADDED","LAST_SEEN","LAST_DELETED"));
        return a.equals(b)?a:"CONFLICT";
    }
    private String packageState(String source){
        var objects=jdbc.queryForList("SELECT OBJECT_TYPE||':'||STATUS FROM SYS.USER_OBJECTS WHERE OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL ORDER BY OBJECT_TYPE",String.class,PACKAGE);
        if(objects.isEmpty())return "MISSING";
        if(!objects.equals(List.of("PACKAGE:VALID","PACKAGE BODY:VALID")))return "CONFLICT";
        for(String type:List.of("PACKAGE","PACKAGE BODY")){
            String actual=String.join("",jdbc.queryForList("SELECT TEXT FROM SYS.USER_SOURCE WHERE NAME=? AND TYPE=? ORDER BY LINE",String.class,PACKAGE,type)).replace("\r","").strip();
            String expected=(type.equals("PACKAGE")?resource("package"):body(source)).substring("CREATE ".length()).replace("\r","").strip();
            if(!actual.equals(expected))return "CONFLICT";
        }return "READY";
    }
    private static String str(Object value){return value==null?"":value.toString();}
    public Status status(String owner,String database){
        String source="",sourceState="READY",error="";
        try{source=auditView();jdbc.queryForObject("SELECT COUNT(*) FROM "+source+" WHERE 1=0 AND END_USER_NAME IS NOT NULL AND END_USER_SECURITY_CONTEXT_ID IS NULL AND RLS_INFO IS NULL",Integer.class);
            jdbc.queryForObject("SELECT LENGTH(RAWTOHEX(SYS.DBMS_CRYPTO.HASH(TO_CLOB('audit-readiness'),4))) FROM DUAL",Integer.class);
        }catch(RuntimeException ex){sourceState="UNAVAILABLE";error=OracleErrorDetails.forDisplay(ex);}
        String store=store(),pkg=source.isEmpty()?"UNAVAILABLE":packageState(source);
        var jobs=jdbc.queryForList("SELECT JOB_TYPE,JOB_ACTION,COMMENTS,ENABLED,STATE,REPEAT_INTERVAL,TO_CHAR(NEXT_RUN_DATE,'YYYY-MM-DD HH24:MI:SS TZH:TZM') NEXT_RUN FROM SYS.USER_SCHEDULER_JOBS WHERE JOB_NAME=?",JOB);
        var job=new LinkedHashMap<String,Object>();String jobState="MISSING";boolean enabled=false;
        if(!jobs.isEmpty()){
            var row=jobs.getFirst();enabled="TRUE".equals(row.get("ENABLED"));
            Object action=row.get("JOB_ACTION");if(action instanceof Clob clob)try{action=clob.getSubString(1,Math.toIntExact(clob.length()));}catch(SQLException ex){throw new IllegalStateException(ex);}
            jobState="PLSQL_BLOCK".equals(row.get("JOB_TYPE"))&&ACTION.equals(str(action))&&MARKER.equals(row.get("COMMENTS"))?"READY":"CONFLICT";
            for(String key:List.of("STATE","REPEAT_INTERVAL","NEXT_RUN"))job.put(key,row.get(key));
            var runs=jdbc.queryForList("SELECT STATUS,ERROR#,TO_CHAR(ACTUAL_START_DATE,'YYYY-MM-DD HH24:MI:SS TZH:TZM') STARTED_AT FROM SYS.USER_SCHEDULER_JOB_RUN_DETAILS WHERE JOB_NAME=? ORDER BY LOG_ID DESC FETCH FIRST 1 ROW ONLY",JOB);
            if(!runs.isEmpty())job.putAll(runs.getFirst());
        }
        Map<String,Object> config=Map.of();
        if("READY".equals(store)){
            var rows=jdbc.queryForList("SELECT INTERVAL_MINUTES,RETENTION_DAYS,TO_CHAR(LAST_SCAN_TO,'YYYY-MM-DD HH24:MI:SS.FF6') LAST_SCAN_TO,TO_CHAR(LAST_SUCCESS,'YYYY-MM-DD HH24:MI:SS TZH:TZM') LAST_SUCCESS,LAST_ADDED,LAST_SEEN,LAST_DELETED FROM "+CONFIG+" WHERE CONFIG_ID=1 AND VERSION_TAG=?",MARKER);
            if(rows.size()==1)config=rows.getFirst();else store="CONFLICT";
        }
        var privileges=jdbc.queryForList("SELECT PRIVILEGE FROM SESSION_PRIVS WHERE PRIVILEGE IN ('CREATE TABLE','CREATE PROCEDURE','CREATE JOB')",String.class);
        var missing=new ArrayList<>(List.of("CREATE TABLE","CREATE PROCEDURE","CREATE JOB"));missing.removeAll(privileges);
        int index=jdbc.queryForObject("SELECT COUNT(*) FROM SYS.USER_OBJECTS WHERE OBJECT_NAME='DBC_AUDIT_ARCHIVE_DT'",Integer.class);
        boolean install=sourceState.equals("READY")&&store.equals("MISSING")&&pkg.equals("MISSING")&&jobState.equals("MISSING")&&missing.isEmpty()&&index==0;
        return new Status(owner,database,source,sourceState,store,pkg,jobState,enabled,install,missing,config,job,error);
    }
    public record Statement(String sql,List<Object> args){}
    private static String fields(String prefix,boolean original){
        return String.join(",",FIELDS.stream().map(f->f.equals("SECURITY_CONTEXT_ID")&&original?"RAWTOHEX("+prefix+"END_USER_SECURITY_CONTEXT_ID) SECURITY_CONTEXT_ID":prefix+f).toList());
    }
    public static Statement listSql(Query q,String view){
        var args=new ArrayList<Object>(List.of(q.from().toString(),q.to().plusDays(1).toString()));
        String where="EVENT_TIMESTAMP_UTC>=TO_TIMESTAMP(?,'YYYY-MM-DD') AND EVENT_TIMESTAMP_UTC<TO_TIMESTAMP(?,'YYYY-MM-DD')";
        if(q.scope().equals("dds")||q.source()==Source.archive)where+=" AND END_USER_NAME IS NOT NULL";
        for(var filter:List.of(new String[]{"DBUSERNAME",q.dbUser()},new String[]{"END_USER_NAME",q.endUser()},new String[]{"OBJECT_SCHEMA",q.objectOwner()},new String[]{"OBJECT_NAME",q.objectName()})){
            if(!filter[1].isEmpty()){where+=" AND "+filter[0]+"=?";args.add(filter[1]);}
        }
        if(!q.context().isEmpty()){where+=" AND "+(q.source()==Source.original?"RAWTOHEX(END_USER_SECURITY_CONTEXT_ID)":"SECURITY_CONTEXT_ID")+"=?";args.add(q.context());}
        if(!q.outcome().equals("all"))where+=" AND RETURN_CODE"+(q.outcome().equals("success")?"=0":"<>0");
        boolean original=q.source()==Source.original;
        String inner="SELECT "+fields("",original)+(original?"":",RECORD_KEY")+" FROM "+(original?view(view):TABLE)+" WHERE "+where
            +" ORDER BY EVENT_TIMESTAMP_UTC DESC,DBID,INSTANCE_ID,SESSIONID,ENTRY_ID,STATEMENT_ID,OBJECT_SCHEMA,OBJECT_NAME OFFSET ? ROWS FETCH NEXT 21 ROWS ONLY";
        args.add((q.page()-1)*20);
        // Numeric audit identifiers can exceed JavaScript's safe integer range.
        var identifiers=Set.of("DBID","INSTANCE_ID","SESSIONID","ENTRY_ID","STATEMENT_ID","SCN");
        String columns=String.join(",",FIELDS.stream().filter(f->!Set.of("SQL_TEXT","RLS_INFO","EVENT_TIMESTAMP_UTC").contains(f)).map(f->identifiers.contains(f)?"TO_CHAR(a."+f+") "+f:"a."+f).toList());
        return new Statement("SELECT "+(original?key():"a.RECORD_KEY")+" RECORD_KEY,TO_CHAR(a.EVENT_TIMESTAMP_UTC,'YYYY-MM-DD HH24:MI:SS.FF6') EVENT_TIMESTAMP_UTC,"+columns+",DBMS_LOB.SUBSTR(a.SQL_TEXT,200,1) SQL_PREVIEW FROM ("+inner+") a",args);
    }
    public Page page(Query q){
        if(q.source()==Source.archive&&!store().equals("READY"))throw new IllegalArgumentException("Archive not installed");
        var statement=listSql(q,q.source()==Source.original?auditView():"");var rows=jdbc.queryForList(statement.sql(),statement.args().toArray());
        return new Page(rows.subList(0,Math.min(20,rows.size())),q.page(),rows.size()>20);
    }
    public Map<String,Object> detail(Selection selection){
        var row=selection.row();var args=new ArrayList<Object>();String from;
        if(selection.query().source()==Source.archive){
            if(!store().equals("READY"))throw new IllegalArgumentException("Archive unavailable");from=TABLE+" a WHERE RECORD_KEY=?";args.add(row.get("RECORD_KEY"));
        }else{
            String predicate="EVENT_TIMESTAMP_UTC=TO_TIMESTAMP(?,'YYYY-MM-DD HH24:MI:SS.FF6')";args.add(row.get("EVENT_TIMESTAMP_UTC"));
            for(String f:List.of("DBID","INSTANCE_ID","SESSIONID","ENTRY_ID","STATEMENT_ID","OBJECT_SCHEMA","OBJECT_NAME")){
                Object value=row.get(f);if(value==null)predicate+=" AND "+f+" IS NULL";else{predicate+=" AND "+f+"=?";args.add(value);}
            }
            from="(SELECT "+fields("",true)+" FROM "+auditView()+" WHERE "+predicate+") a WHERE "+key()+"=?";args.add(row.get("RECORD_KEY"));
        }
        var rows=jdbc.query("SELECT a.SQL_TEXT,a.RLS_INFO FROM "+from+" FETCH FIRST 2 ROWS ONLY",(r,n)->{
            var result=new LinkedHashMap<String,Object>(row);readClob(r,"SQL_TEXT",result);readClob(r,"RLS_INFO",result);return result;
        },args.toArray());
        if(rows.size()!=1)throw new IllegalArgumentException("Record expired, changed or ambiguous");return rows.getFirst();
    }
    private static void readClob(ResultSet r,String name,Map<String,Object> into)throws SQLException{
        try(var reader=r.getCharacterStream(name)){
            if(reader==null){into.put(name,"");return;}char[] value=new char[100001];int used=0,n;
            while(used<value.length&&(n=reader.read(value,used,value.length-used))!=-1)used+=n;
            into.put(name,new String(value,0,Math.min(used,100000)));into.put(name+"_TRUNCATED",used>100000);
        }catch(IOException ex){throw new SQLException("Audit CLOB read failed",ex);}
    }
    public void apply(List<String> statements){
        int completed=0;
        try{for(String sql:statements){jdbc.execute(sql);completed++;
            if(sql.startsWith("CREATE PACKAGE")&&jdbc.queryForObject("SELECT COUNT(*) FROM SYS.USER_ERRORS WHERE NAME=?",Integer.class,PACKAGE)>0)throw new IllegalStateException("Audit package compilation failed");
        }}catch(RuntimeException ex){throw new PartialChange(completed,ex);}
    }
    public static final class PartialChange extends RuntimeException{
        public final int completed;public PartialChange(int count,RuntimeException cause){super("Partial audit setup",cause);completed=count;}
    }
}
