package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.AccountSetup;
import com.dbcompanion.model.AccountSetup.*;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Password-bearing DDL uses raw JDBC and never enters exception text or application logs. */
@Service
public class AccountSetupService {
    public record PrivilegePlan(String token,String name,long userId,String operation,List<String> statements,java.time.Instant expires) {}
    public static List<String> privilegeSql(String name,String operation){
        AccountSetup.validate(new Input(name,Preset.LIMITED));
        if(operation==null)throw AccountSetup.error(400,"invalid");
        String privilege=switch(operation){case "TEXT"->"EXECUTE ON CTXSYS.CTX_DDL";case "FUNCTION"->"CREATE PROCEDURE";case "TABLE"->"CREATE TABLE";case "PROFILE"->"EXECUTE ON DBMS_CLOUD_AI";case "AGENT"->"EXECUTE ON DBMS_CLOUD_AI_AGENT";case "CREDENTIAL"->"EXECUTE ON DBMS_CLOUD";case "JOB"->"CREATE JOB";case "GRAPH"->"CREATE PROPERTY GRAPH, CREATE VIEW";case "RDF"->"CREATE TABLE, CREATE VIEW, CREATE PROCEDURE, CREATE SEQUENCE, CREATE TRIGGER, CREATE TYPE";default->throw AccountSetup.error(400,"invalid");};
        return List.of("GRANT "+privilege+" TO \""+name+"\"");
    }
    private long ordinaryUserId(Connection c,String name)throws SQLException{
        try(var q=c.prepareStatement("SELECT USER_ID FROM DBA_USERS WHERE USERNAME=? AND ORACLE_MAINTAINED='N' AND COMMON='NO'")){q.setQueryTimeout(15);q.setString(1,name);try(var r=q.executeQuery()){if(!r.next())throw AccountSetup.error(409,"identityChanged");return r.getLong(1);}}
    }
    public PrivilegePlan privilegePreview(PoolSession s,String name,String operation){var sql=privilegeSql(name,operation);return run(s,c->new PrivilegePlan(UUID.randomUUID().toString(),name,ordinaryUserId(c,name),operation,sql,java.time.Instant.now().plusSeconds(300)));}
    public Status privilegeApply(PoolSession s,PrivilegePlan plan){run(s,c->{
        if(ordinaryUserId(c,plan.name())!=plan.userId()||!plan.statements().equals(privilegeSql(plan.name(),plan.operation())))throw AccountSetup.error(409,"identityChanged");
        for(var sql:plan.statements())try(var q=c.createStatement()){q.setQueryTimeout(30);q.execute(sql);}return null;
    });return status(s,plan.name());}
    public static void admin(PoolSession s){if(!"ADMIN".equals(s.metadata().info().username())||!"ADMIN".equals(s.metadata().selectedSchema()))throw AccountSetup.error(403,"admin");}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T run(PoolSession s,Work<T> work){admin(s);synchronized(s){try(var c=s.pool().getConnection()){return work.run(c);}catch(SQLException ex){throw AccountSetup.error(503,"failed");}}}
    private long userId(Connection c,String name)throws SQLException{
        try(var q=c.prepareStatement("SELECT USER_ID FROM DBA_USERS WHERE USERNAME=?")){q.setQueryTimeout(15);q.setString(1,name);try(var r=q.executeQuery()){return r.next()?r.getLong(1):-1;}}
    }
    public Plan preview(PoolSession s,Input input){AccountSetup.validate(input);return run(s,c->{if(userId(c,input.name())!=-1)throw AccountSetup.error(409,"exists");return AccountSetup.plan(input);});}
    public Status status(PoolSession s,String name){AccountSetup.validate(new Input(name,Preset.LIMITED));return run(s,c->{
        String state="";try(var q=c.prepareStatement("SELECT ACCOUNT_STATUS FROM DBA_USERS WHERE USERNAME=?")){q.setQueryTimeout(15);q.setString(1,name);try(var r=q.executeQuery()){if(r.next())state=r.getString(1);}}
        var privileges=strings(c,"SELECT PRIVILEGE FROM DBA_SYS_PRIVS WHERE GRANTEE=? ORDER BY PRIVILEGE",name);
        var packages=strings(c,"SELECT OWNER||'.'||TABLE_NAME||':'||PRIVILEGE FROM DBA_TAB_PRIVS WHERE GRANTEE=? ORDER BY OWNER,TABLE_NAME,PRIVILEGE",name);
        long quota=0;try(var q=c.prepareStatement("SELECT MAX_BYTES FROM DBA_TS_QUOTAS WHERE USERNAME=? AND TABLESPACE_NAME='DATA'")){q.setQueryTimeout(15);q.setString(1,name);try(var r=q.executeQuery()){if(r.next())quota=r.getLong(1);}}
        return new Status(name,!state.isEmpty(),state,privileges,packages,quota);
    });}
    private List<String> strings(Connection c,String sql,String name)throws SQLException{try(var q=c.prepareStatement(sql)){q.setQueryTimeout(15);q.setString(1,name);try(var r=q.executeQuery()){var values=new ArrayList<String>();while(r.next())values.add(r.getString(1));return List.copyOf(values);}}}
    /** The returned database user id binds subsequent setup to this newly created identity. */
    public long create(PoolSession s,Plan plan,String password){AccountSetup.password(password);return run(s,c->{
        if(userId(c,plan.input().name())!=-1)throw AccountSetup.error(409,"exists");
        try(var statement=c.createStatement()){statement.setQueryTimeout(30);statement.execute("CREATE USER \""+plan.input().name()+"\" IDENTIFIED BY \""+password+"\" DEFAULT TABLESPACE DATA");}
        long id=userId(c,plan.input().name());if(id<0)throw AccountSetup.error(503,"failed");return id;
    });}
    public Status configure(PoolSession s,Plan plan,long id){run(s,c->{
        if(id<0||userId(c,plan.input().name())!=id)throw AccountSetup.error(409,"identityChanged");
        for(var sql:AccountSetup.grants(plan.input()))try(var statement=c.createStatement()){statement.setQueryTimeout(30);statement.execute(sql);}
        return null;
    });return status(s,plan.input().name());}
}
