package com.dbcompanion.common.db;

import com.dbcompanion.model.AuditHistory.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;

public final class AuditHistorySql {
    private AuditHistorySql(){}
    public static final String TABLE="DBC_AUDIT_ARCHIVE",CONFIG="DBC_AUDIT_ARCHIVE_CONFIG",PACKAGE="DBC_AUDIT_ARCHIVER",JOB="DBC_AUDIT_CAPTURE",MARKER="DBC_AUDIT_ARCHIVE_V1";
    public static final String ACTION="BEGIN DBC_AUDIT_ARCHIVER.COLLECT; END;";
    public static final List<String> FIELDS=List.of("DBID","INSTANCE_ID","SESSIONID","ENTRY_ID","STATEMENT_ID","EXECUTION_ID","SCN","AUDIT_TYPE","EVENT_TIMESTAMP_UTC","DBUSERNAME","END_USER_NAME","SECURITY_CONTEXT_ID","ACTION_NAME","OBJECT_SCHEMA","OBJECT_NAME","RETURN_CODE","UNIFIED_AUDIT_POLICIES","SQL_TEXT","RLS_INFO");
    public static String view(String name){if(!Set.of("SYS.UNIFIED_AUDIT_TRAIL","AUDSYS.UNIFIED_AUDIT_TRAIL").contains(name))throw new IllegalArgumentException("Invalid Oracle audit source");return name;}
    public static String resource(String name){try{return new ClassPathResource("sql/audit-history/"+name+".sql").getContentAsString(StandardCharsets.UTF_8).strip();}catch(IOException ex){throw new IllegalStateException(ex);}}
    public static String literal(String v){return "'"+v.replace("'","''")+"'";}
    public static String key(){
        var fields=new ArrayList<String>();fields.add("'DATABASE' VALUE SYS_CONTEXT('USERENV','DB_UNIQUE_NAME')");
        for(String f:FIELDS)fields.add(literal(f)+" VALUE "+(f.equals("EVENT_TIMESTAMP_UTC")?"TO_CHAR(a."+f+",'YYYY-MM-DD\"T\"HH24:MI:SS.FF6')":"a."+f));
        // Hash the complete CLOB, not a truncated SQL preview. Null and delimiters are preserved by JSON.
        return "RAWTOHEX(SYS.DBMS_CRYPTO.HASH(JSON_OBJECT("+String.join(",",fields)+" NULL ON NULL RETURNING CLOB),4))";
    }
    public static String body(String view){return resource("body").replace("@@KEY@@",key()).replace("@@AUDIT_VIEW@@",view(view));}
    public static List<String> install(String view,Settings s){
        return List.of(resource("archive"),"COMMENT ON TABLE "+TABLE+" IS '"+MARKER+"'",
            "CREATE INDEX DBC_AUDIT_ARCHIVE_DT ON "+TABLE+" (EVENT_TIMESTAMP_UTC)",
            resource("config"),"COMMENT ON TABLE "+CONFIG+" IS '"+MARKER+"'",
            "INSERT INTO "+CONFIG+" (CONFIG_ID,VERSION_TAG,INTERVAL_MINUTES,RETENTION_DAYS) VALUES (1,'"+MARKER+"',"+s.minutes()+","+s.retentionDays()+")",
            resource("package"),body(view),
            "BEGIN DBMS_SCHEDULER.CREATE_JOB(job_name=>'"+JOB+"',job_type=>'PLSQL_BLOCK',job_action=>"+literal(ACTION)+",start_date=>SYSTIMESTAMP,repeat_interval=>"+literal(interval(s))+",enabled=>FALSE,auto_drop=>FALSE,comments=>'"+MARKER+"'); END;",
            "BEGIN DBMS_SCHEDULER.SET_ATTRIBUTE('"+JOB+"','logging_level',DBMS_SCHEDULER.LOGGING_RUNS); DBMS_SCHEDULER.ENABLE('"+JOB+"'); END;");
    }
    public static String interval(Settings s){return "FREQ=MINUTELY;INTERVAL="+s.minutes();}
    public static String script(List<String> statements){
        return String.join("\n\n",statements.stream().map(sql->sql+(sql.startsWith("CREATE PACKAGE")||sql.startsWith("BEGIN ")?"\n/":";")).toList());
    }
    public static String help(String source){
        return "-- Read original DDS audit records (UTC). This does not enable an audit policy.\n"
            +"SELECT EVENT_TIMESTAMP_UTC, DBUSERNAME, END_USER_NAME,\n"
            +"       RAWTOHEX(END_USER_SECURITY_CONTEXT_ID) AS SECURITY_CONTEXT_ID,\n"
            +"       ACTION_NAME, OBJECT_SCHEMA, OBJECT_NAME, RETURN_CODE,\n"
            +"       UNIFIED_AUDIT_POLICIES, SQL_TEXT, RLS_INFO\nFROM "+view(source)+"\n"
            +"WHERE END_USER_NAME IS NOT NULL\n"
            +"  AND EVENT_TIMESTAMP_UTC >= SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '1' DAY\n"
            +"ORDER BY EVENT_TIMESTAMP_UTC DESC;\n\n"
            +"-- INSTALL ONCE in the archive owner's schema. DDL commits.\n"
            +"-- Requires audit SELECT, DBMS_CRYPTO, CREATE TABLE, CREATE PROCEDURE, CREATE JOB.\n"
            +"-- The final block enables hourly DDS-only capture. Archive automatic deletion is OFF.\n"
            +script(install(source,new Settings(60,0)))+"\n\n"
            +"-- Read archived records after installation. Object records are not SQL execution counts.\n"
            +"SELECT EVENT_TIMESTAMP_UTC, DBUSERNAME, END_USER_NAME, SECURITY_CONTEXT_ID,\n"
            +"       ACTION_NAME, OBJECT_SCHEMA, OBJECT_NAME, RETURN_CODE, SQL_TEXT, RLS_INFO\n"
            +"FROM "+TABLE+" ORDER BY EVENT_TIMESTAMP_UTC DESC;\n\n"
            +"-- Collector checkpoint, counters and scheduler result.\n"
            +"SELECT * FROM "+CONFIG+";\n"
            +"SELECT JOB_NAME, ENABLED, STATE, REPEAT_INTERVAL, NEXT_RUN_DATE\n"
            +"FROM USER_SCHEDULER_JOBS WHERE JOB_NAME='"+JOB+"';\n"
            +"SELECT LOG_DATE, STATUS, ERROR#, ADDITIONAL_INFO FROM USER_SCHEDULER_JOB_RUN_DETAILS\n"
            +"WHERE JOB_NAME='"+JOB+"' ORDER BY LOG_ID DESC;";
    }
    /** Separate copy-only examples by effect. Read filters are binds, not installation SQL. */
    public static Map<String,String> helpSections(String source){
        String query="SELECT EVENT_TIMESTAMP_UTC, DBUSERNAME, END_USER_NAME, ACTION_NAME, OBJECT_SCHEMA, OBJECT_NAME, RETURN_CODE, SQL_TEXT, RLS_INFO\n"
                +"FROM "+view(source)+"\nWHERE EVENT_TIMESTAMP_UTC >= TO_TIMESTAMP(:from_date,'YYYY-MM-DD')\n"
                +"AND EVENT_TIMESTAMP_UTC < TO_TIMESTAMP(:to_date,'YYYY-MM-DD') + INTERVAL '1' DAY\n"
                +"AND (:db_user IS NULL OR DBUSERNAME=:db_user)\nAND (:end_user IS NULL OR END_USER_NAME=:end_user)\n"
                +"AND (:object_owner IS NULL OR OBJECT_SCHEMA=:object_owner)\nAND (:object_name IS NULL OR OBJECT_NAME=:object_name)\n"
                +"AND (:scope='all' OR END_USER_NAME IS NOT NULL)\nAND (:outcome='all' OR (:outcome='success' AND RETURN_CODE=0) OR (:outcome='error' AND RETURN_CODE<>0))\n"
                +"AND (:context IS NULL OR RAWTOHEX(END_USER_SECURITY_CONTEXT_ID)=:context)\nORDER BY EVENT_TIMESTAMP_UTC DESC FETCH FIRST 101 ROWS ONLY;";
        var result=new LinkedHashMap<String,String>();result.put("READ",query);
        result.put("INSTALL","-- DDL commits. Final block enables collection. No SQL in this panel is executed automatically.\n"+script(install(source,new Settings(60,0))));
        result.put("STATUS","SELECT * FROM "+CONFIG+";\nSELECT JOB_NAME,ENABLED,STATE,REPEAT_INTERVAL,NEXT_RUN_DATE FROM USER_SCHEDULER_JOBS WHERE JOB_NAME='"+JOB+"';");
        result.put("PAUSE","BEGIN DBMS_SCHEDULER.DISABLE('"+JOB+"'); END;\n/");
        return Collections.unmodifiableMap(result);
    }
    public static Map<String,String> helpSections(String source,boolean archived){
        var result=new LinkedHashMap<>(helpSections(source));
        if(archived)result.put("READ",result.get("READ").replace("FROM "+view(source),"FROM "+TABLE).replace("RAWTOHEX(END_USER_SECURITY_CONTEXT_ID)","SECURITY_CONTEXT_ID"));
        return Collections.unmodifiableMap(result);
    }
    public static List<String> plan(Status status,Operation op,Settings s){
        if(op==null||s==null)throw new IllegalArgumentException("Missing operation");
        if(op==Operation.INSTALL){if(!status.canInstall())throw new IllegalArgumentException("Installation unavailable");return install(status.source(),s);}
        if(!status.ready()||(!"READY".equals(status.sourceState())&&op!=Operation.PAUSE))throw new IllegalArgumentException("Collector unavailable");
        if("RUNNING".equals(status.job().get("STATE"))&&op!=Operation.RUN)throw new IllegalArgumentException("Wait for running collection");
        return switch(op){
            case CONFIGURE -> List.of("BEGIN UPDATE "+CONFIG+" SET INTERVAL_MINUTES="+s.minutes()+",RETENTION_DAYS="+s.retentionDays()+" WHERE CONFIG_ID=1; DBMS_SCHEDULER.SET_ATTRIBUTE('"+JOB+"','repeat_interval',"+literal(interval(s))+"); COMMIT; END;");
            case PAUSE -> List.of("BEGIN DBMS_SCHEDULER.DISABLE('"+JOB+"'); END;");
            case RESUME -> List.of("BEGIN DBMS_SCHEDULER.ENABLE('"+JOB+"'); END;");
            case RUN -> List.of("BEGIN DBMS_SCHEDULER.RUN_JOB('"+JOB+"',use_current_session=>FALSE); END;");
            default -> throw new IllegalArgumentException("Invalid operation");
        };
    }
}
