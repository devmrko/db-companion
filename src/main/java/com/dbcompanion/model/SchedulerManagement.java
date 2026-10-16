package com.dbcompanion.model;

import java.util.*;

/** Owner-only inline PL/SQL jobs. Execution is submitted, never reported as completed here. */
public final class SchedulerManagement {
    private SchedulerManagement() {}
    public record Draft(String schema,String action,String name,String code,int minutes,String comments) {}
    public record State(List<Map<String,String>> jobs,Set<String> privileges) {}
    public record Request(Draft draft,String revision) {}
    public record Preview(String token,String confirmation,String sql,String revision,Draft draft,Draft previous,String executionCode) {}
    public record Apply(String token,String confirmation,boolean consent) {}
    public static final class Failure extends RuntimeException {public Failure(String key){super(key);}}
    public static void require(boolean ok,String key){if(!ok)throw new Failure(key);}
    public static String id(String s){require(s!=null&&s.matches("[A-Z][A-Z0-9_]{0,127}"),"invalid");return "\""+s+"\"";}
    public static String quote(String s){return "'"+s.replace("'","''")+"'";}
    public static Map<String,String> target(State s,String name){return s.jobs().stream().filter(r->name.equals(r.get("JOB_NAME"))).findFirst().orElse(null);}
    private static boolean empty(String s){return s==null||s.isEmpty();}
    public static void editable(Map<String,String> r){
        require(r!=null,"missing");
        require("PLSQL_BLOCK".equals(r.get("JOB_TYPE"))&&"REGULAR".equals(r.get("JOB_STYLE"))&&"FALSE".equals(r.get("AUTO_DROP"))&&!"TRUE".equals(r.get("SYSTEM")),"unsupported");
        require("0".equals(r.get("NUMBER_OF_ARGUMENTS"))&&"DEFAULT_JOB_CLASS".equals(r.get("JOB_CLASS")),"unsupported");
        for(String k:List.of("PROGRAM_NAME","SCHEDULE_NAME","CREDENTIAL_NAME","DESTINATION","EVENT_CONDITION"))require(empty(r.get(k)),"unsupported");
        require(!r.get("JOB_NAME").startsWith("DBC_"),"protected");
        require(!"RUNNING".equals(r.get("STATE")),"running");
    }
    public static Draft previous(String owner,Map<String,String> r){
        editable(r);String interval=r.get("REPEAT_INTERVAL");
        require(interval!=null&&interval.matches("FREQ=MINUTELY;INTERVAL=[0-9]{1,5}"),"unsupported");
        return new Draft(owner,"UPDATE",r.get("JOB_NAME"),r.get("JOB_ACTION"),Integer.parseInt(interval.substring(interval.lastIndexOf('=')+1)),Objects.toString(r.get("COMMENTS"),""));
    }
    public static String sql(Draft d,State s,String owner){
        require(d!=null&&owner.equals(d.schema()),"ownSchema");String job=quote(id(owner)+"."+id(d.name()));
        require(Set.of("CREATE","UPDATE","RUN","ENABLE","DISABLE").contains(d.action()),"invalid");
        require(s.privileges().contains("CREATE JOB"),"privilege");
        var row=target(s,d.name());
        if(d.action().equals("CREATE")){require(row==null,"exists");require(!d.name().startsWith("DBC_"),"protected");}else editable(row);
        if(Set.of("CREATE","UPDATE").contains(d.action())){
            require(d.code()!=null&&!d.code().isBlank()&&d.code().length()<=16000&&d.code().indexOf('\0')<0&&d.minutes()>=1&&d.minutes()<=10080&&d.comments()!=null&&d.comments().length()<=500,"invalid");
            if(row!=null){require("FALSE".equals(row.get("ENABLED")),"disableFirst");previous(owner,row);}
            String interval=quote("FREQ=MINUTELY;INTERVAL="+d.minutes());
            if(d.action().equals("CREATE"))return "BEGIN DBMS_SCHEDULER.CREATE_JOB(job_name => "+job+", job_type => 'PLSQL_BLOCK', job_action => "+quote(d.code())+", repeat_interval => "+interval+", enabled => FALSE, auto_drop => FALSE, comments => "+quote(d.comments())+"); END;";
            return "BEGIN DBMS_SCHEDULER.SET_ATTRIBUTE("+job+", 'job_action', "+quote(d.code())+"); DBMS_SCHEDULER.SET_ATTRIBUTE("+job+", 'repeat_interval', "+interval+"); DBMS_SCHEDULER.SET_ATTRIBUTE("+job+", 'comments', "+quote(d.comments())+"); END;";
        }
        if(d.action().equals("RUN")){require("FALSE".equals(row.get("ENABLED")),"disableFirst");return "BEGIN DBMS_SCHEDULER.RUN_JOB("+job+", use_current_session => FALSE); END;";}
        return "BEGIN DBMS_SCHEDULER."+d.action()+"("+job+"); END;";
    }
    public static void verify(Draft d,State s){
        if(d.action().equals("RUN"))return;
        var r=target(s,d.name());require(r!=null,"verifyFailed");
        if(Set.of("ENABLE","DISABLE").contains(d.action()))require((d.action().equals("ENABLE")?"TRUE":"FALSE").equals(r.get("ENABLED")),"verifyFailed");
        else require(d.code().equals(r.get("JOB_ACTION"))&&("FREQ=MINUTELY;INTERVAL="+d.minutes()).equals(r.get("REPEAT_INTERVAL"))&&d.comments().equals(Objects.toString(r.get("COMMENTS"),""))&&"FALSE".equals(r.get("ENABLED")),"verifyFailed");
    }
}
