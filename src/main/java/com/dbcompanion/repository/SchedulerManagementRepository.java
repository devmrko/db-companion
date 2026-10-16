package com.dbcompanion.repository;

import com.dbcompanion.model.SchedulerManagement.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class SchedulerManagementRepository {
    private final JdbcTemplate jdbc;
    public SchedulerManagementRepository(JdbcTemplate template){jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(15);}
    public State state(){
        var jobs=jdbc.query("SELECT JOB_NAME,JOB_TYPE,JOB_STYLE,JOB_ACTION,REPEAT_INTERVAL,COMMENTS,ENABLED,STATE,AUTO_DROP,SYSTEM,NUMBER_OF_ARGUMENTS,JOB_CLASS,PROGRAM_NAME,SCHEDULE_NAME,CREDENTIAL_NAME,DESTINATION,EVENT_CONDITION FROM USER_SCHEDULER_JOBS ORDER BY JOB_NAME FETCH FIRST 1001 ROWS ONLY",(rs,n)->{
            if(n>=1000)throw new Failure("limit");var r=new TreeMap<String,String>();var meta=rs.getMetaData();for(int i=1;i<=meta.getColumnCount();i++)r.put(meta.getColumnLabel(i),rs.getString(i));return Collections.unmodifiableMap(r);
        });
        return new State(jobs,new TreeSet<>(jdbc.queryForList("SELECT PRIVILEGE FROM SESSION_PRIVS",String.class)));
    }
    public void execute(String sql){jdbc.execute((ConnectionCallback<Void>)c->{try(var statement=c.createStatement()){statement.setQueryTimeout(30);statement.execute(sql);}return null;});}
}
