package com.dbcompanion.repository;

import com.dbcompanion.model.Scheduler;
import com.dbcompanion.model.Scheduler.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

@Repository
public class SchedulerRepository {
    public enum View {JOBS,PROGRAMS,SCHEDULES,JOB_ARGS,PROGRAM_ARGS,JOB_RUN_DETAILS}
    private static final String[] LIST={"JOB_NAME","COMMENTS","ENABLED","STATE","JOB_TYPE","LAST_START_DATE","LAST_RUN_DURATION","NEXT_RUN_DATE"};
    private static final String[] JOB={"JOB_NAME","COMMENTS","ENABLED","STATE","JOB_TYPE","JOB_ACTION","JOB_STYLE","JOB_CLASS","JOB_CREATOR","PROGRAM_OWNER","PROGRAM_NAME","SCHEDULE_OWNER","SCHEDULE_NAME","SCHEDULE_TYPE","START_DATE","REPEAT_INTERVAL","END_DATE","LAST_START_DATE","LAST_RUN_DURATION","NEXT_RUN_DATE","RUN_COUNT","FAILURE_COUNT","RETRY_COUNT","MAX_RUNS","MAX_FAILURES","MAX_RUN_DURATION","AUTO_DROP","RESTARTABLE","LOGGING_LEVEL","STORE_OUTPUT","NUMBER_OF_ARGUMENTS","CREDENTIAL_OWNER","CREDENTIAL_NAME","DESTINATION_OWNER","DESTINATION","EVENT_QUEUE_OWNER","EVENT_QUEUE_NAME","EVENT_CONDITION"};
    private static final String[] PROGRAM={"PROGRAM_NAME","PROGRAM_TYPE","PROGRAM_ACTION","NUMBER_OF_ARGUMENTS","ENABLED","COMMENTS"};
    private static final String[] SCHEDULE={"SCHEDULE_NAME","SCHEDULE_TYPE","START_DATE","REPEAT_INTERVAL","END_DATE","EVENT_QUEUE_OWNER","EVENT_QUEUE_NAME","EVENT_CONDITION","COMMENTS"};
    private static final String[] RUNS={"LOG_ID","JOB_SUBNAME","LOG_DATE","ACTUAL_START_DATE","RUN_DURATION","STATUS","ERROR#"};
    private static final String[] RUN={"LOG_ID","JOB_NAME","JOB_SUBNAME","LOG_DATE","STATUS","ERROR#","REQ_START_DATE","ACTUAL_START_DATE","RUN_DURATION","CPU_USED","INSTANCE_ID","SESSION_ID","DESTINATION_OWNER","DESTINATION","CREDENTIAL_OWNER","CREDENTIAL_NAME","ADDITIONAL_INFO","ERRORS","OUTPUT"};
    private final JdbcTemplate jdbc;
    public SchedulerRepository(JdbcTemplate jdbc){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);}
    private static String nameColumn(View view){return switch(view){case PROGRAMS,PROGRAM_ARGS->"PROGRAM_NAME";case SCHEDULES->"SCHEDULE_NAME";default->"JOB_NAME";};}
    public static String viewName(View view,boolean own,boolean visible){return "SYS."+(own?"USER":visible?"ALL":"DBA")+"_SCHEDULER_"+view;}
    public static String selectSql(View view,String prefix,AgentViewColumns columns,List<String> fields,boolean named,String before,String logId,int limit){
        if(!Set.of("USER","ALL","DBA").contains(prefix)||limit<1||limit>5001)throw new IllegalArgumentException("Invalid catalog request");
        columns.required(nameColumn(view));if(!prefix.equals("USER"))columns.required("OWNER");
        var selected=new ArrayList<String>();
        for(String field:fields){
            if(field.equals("HAS_ANYDATA")){
                String column=columns.optional(view==View.PROGRAM_ARGS?"DEFAULT_ANYDATA_VALUE":"ANYDATA_VALUE");
                selected.add((column==null?"NULL":"CASE WHEN "+column+" IS NULL THEN 'FALSE' ELSE 'TRUE' END")+" AS HAS_ANYDATA");
            }else{
                if(!field.matches("[A-Z][A-Z0-9_#]*"))throw new IllegalArgumentException("Invalid catalog field");
                selected.add(columns.projection(field)+" AS \""+field+"\"");
            }
        }
        String sql="SELECT "+String.join(", ",selected)+" FROM SYS."+prefix+"_SCHEDULER_"+view+" WHERE 1=1";
        if(!prefix.equals("USER"))sql+=" AND OWNER = ?";
        if(named)sql+=" AND "+nameColumn(view)+" = ?";
        if(view==View.JOB_RUN_DETAILS){
            columns.required("LOG_ID");
            if(Scheduler.present(logId))sql+=" AND LOG_ID = ?";
            else if(Scheduler.present(before))sql+=" AND LOG_ID < ?";
            sql+=" ORDER BY LOG_ID DESC";
        }else sql+=" ORDER BY "+(view==View.JOB_ARGS||view==View.PROGRAM_ARGS?columns.required("ARGUMENT_POSITION"):nameColumn(view));
        return sql+" FETCH FIRST "+limit+" ROWS ONLY";
    }
    private Rows read(View view,String schema,String login,String name,List<String> fields,String before,String id,int limit){
        boolean own=schema.equals(login);Rows data=read(view,schema,own,false,name,fields,before,id,limit);
        return !own&&data.status().equals("ACCESS_REQUIRED")?read(view,schema,false,true,name,fields,before,id,limit):data;
    }
    private Rows read(View view,String schema,boolean own,boolean visible,String name,List<String> fields,String before,String id,int limit){
        String source=viewName(view,own,visible),prefix=own?"USER":visible?"ALL":"DBA";
        try{
            var columns=jdbc.query("SELECT * FROM "+source+" WHERE 1=0",(ResultSetExtractor<AgentViewColumns>)r->{
                var names=new ArrayList<String>();var meta=r.getMetaData();for(int i=1;i<=meta.getColumnCount();i++)names.add(meta.getColumnName(i));return new AgentViewColumns(names);
            });
            var args=new ArrayList<Object>();if(!own)args.add(schema);if(name!=null)args.add(name);
            if(Scheduler.present(id))args.add(new BigDecimal(id));else if(Scheduler.present(before))args.add(new BigDecimal(before));
            String sql=selectSql(view,prefix,columns,fields,name!=null,before,id,limit);
            List<Map<String,String>> rows=jdbc.query(sql,(r,n)->{var row=new LinkedHashMap<String,String>();for(int i=0;i<fields.size();i++)row.put(fields.get(i),r.getString(i+1));return row;},args.toArray());
            if(rows.size()>5000)throw new LimitExceeded();
            return new Rows(rows,source,"AVAILABLE","",Instant.now().toString());
        }catch(RuntimeException ex){return new Rows(List.of(),source,status(ex),CredentialCatalogRepository.error(ex),Instant.now().toString());}
    }
    public static String status(RuntimeException ex){return ex instanceof LimitExceeded?"LIMIT":ex instanceof IllegalStateException&&ex.getMessage()!=null&&ex.getMessage().startsWith("Required catalog column missing:")?"UNSUPPORTED":CredentialCatalogRepository.status(ex);}
    private static final class LimitExceeded extends RuntimeException {}
    public Rows list(String schema,String login){return read(View.JOBS,schema,login,null,List.of(LIST),"","",5001);}
    private Rows one(View view,String schema,String login,String name,String[] fields){
        Rows rows=read(view,schema,login,name,List.of(fields),"","",2);
        if(!rows.status().equals("AVAILABLE"))return rows;
        if(rows.items().size()!=1)return new Rows(List.of(),rows.source(),rows.items().isEmpty()?"NOT_FOUND":"ERROR","",rows.checkedAt());return rows;
    }
    public Detail detail(String schema,String login,String name){
        Rows job=one(View.JOBS,schema,login,name,JOB),program=Rows.empty("NOT_APPLICABLE"),schedule=Rows.empty("NOT_APPLICABLE"),args=Rows.empty("NOT_APPLICABLE"),programArgs=Rows.empty("NOT_APPLICABLE");
        if(!job.status().equals("AVAILABLE"))return new Detail(job,program,schedule,args,programArgs);
        var data=job.first();
        args=read(View.JOB_ARGS,schema,login,name,List.of("ARGUMENT_POSITION","ARGUMENT_NAME","ARGUMENT_TYPE","VALUE","HAS_ANYDATA"),"","",5001);
        if(Scheduler.present(data.get("PROGRAM_NAME"))){
            String owner=Objects.toString(data.get("PROGRAM_OWNER"),schema),target=data.get("PROGRAM_NAME");
            program=one(View.PROGRAMS,owner,login,target,PROGRAM);
            programArgs=read(View.PROGRAM_ARGS,owner,login,target,List.of("ARGUMENT_POSITION","ARGUMENT_NAME","ARGUMENT_TYPE","DEFAULT_VALUE","METADATA_ATTRIBUTE","HAS_ANYDATA"),"","",5001);
        }
        if(Scheduler.present(data.get("SCHEDULE_NAME"))){
            schedule=Set.of("WINDOW","WINDOW_GROUP").contains(Objects.toString(data.get("SCHEDULE_TYPE"),""))?Rows.empty("NOT_EXPANDED"):
                one(View.SCHEDULES,Objects.toString(data.get("SCHEDULE_OWNER"),schema),login,data.get("SCHEDULE_NAME"),SCHEDULE);
        }
        return new Detail(job,program,schedule,args,programArgs);
    }
    public Page runs(String schema,String login,String name,String before){Scheduler.cursor(before);return Page.from(read(View.JOB_RUN_DETAILS,schema,login,name,List.of(RUNS),before,"",11));}
    public Rows run(String schema,String login,String name,String id){
        Scheduler.cursor(id);if(id.isEmpty())throw new IllegalArgumentException("Missing log ID");
        Rows rows=read(View.JOB_RUN_DETAILS,schema,login,name,List.of(RUN),"",id,2);
        return rows.status().equals("AVAILABLE")&&rows.items().isEmpty()?new Rows(List.of(),rows.source(),"NOT_FOUND","",rows.checkedAt()):rows;
    }
}
