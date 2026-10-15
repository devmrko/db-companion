package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.MetadataSql;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiAssistant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Executes only the bundled public fixture in the login owner's dedicated schema. */
@Service
public class ScottDemoService {
    public record Plan(String token,String owner,List<String> statements,Instant expires) {}
    public record Status(String owner,List<String> objects,List<String> missing,List<String> adminSql,boolean canInstall) {}
    public record Result(String owner,long departments,long employees,java.math.BigDecimal salary,java.math.BigDecimal compensation,boolean verified) {}
    public static AiAssistant.Failure error(int status,String key){return new AiAssistant.Failure(status,"scottSetup."+key,UiMessages.text("scottSetup."+key,key));}
    public static String owner(PoolSession s){String name=s.metadata().info().username();if(!name.equals(s.metadata().selectedSchema())||Set.of("ADMIN","SYS","SYSTEM").contains(name))throw error(403,"owner");return name;}
    public static List<String> statements(){
        try{
            String fixture=new ClassPathResource("demo/scott/fixture.sql").getContentAsString(StandardCharsets.UTF_8);
            int start=fixture.indexOf("CREATE TABLE DEPT (");if(start<0)throw new IllegalStateException("Missing fixture");
            // This is a fixed bundled fixture, not a SQL upload or general SQL runner.
            return Arrays.stream(fixture.substring(start).split(";")).map(String::trim).filter(v->!v.isEmpty()&&!v.equals("COMMIT")).toList();
        }catch(IOException ex){throw new IllegalStateException("Missing fixture",ex);}
    }
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T run(PoolSession s,Work<T> work){owner(s);synchronized(s){try(var c=s.pool().getConnection()){c.setSchema(owner(s));return work.run(c);}catch(SQLException ex){throw new AiAssistant.Failure(503,"scottSetup.databaseFailure",error(503,"failed").getMessage()+" (ORA-"+String.format(Locale.ROOT,"%05d",Math.abs(ex.getErrorCode()))+")");}}}
    private List<String> objects(Connection c)throws SQLException{try(var q=c.createStatement()){q.setQueryTimeout(15);try(var r=q.executeQuery("SELECT OBJECT_NAME||':'||OBJECT_TYPE FROM USER_OBJECTS WHERE OBJECT_NAME IN ('EMP','DEPT','PK_EMP','PK_DEPT','FK_EMP_DEPT') UNION ALL SELECT CONSTRAINT_NAME||':CONSTRAINT' FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME IN ('PK_EMP','PK_DEPT','FK_EMP_DEPT')")){var values=new ArrayList<String>();while(r.next())values.add(r.getString(1));return List.copyOf(values);}}}
    private boolean available(Connection c,String sql)throws SQLException{try(var q=c.createStatement()){q.setQueryTimeout(15);try(var r=q.executeQuery(sql)){return r.next()&&r.getLong(1)>0;}}}
    private List<String> missing(Connection c)throws SQLException{
        var values=new ArrayList<String>();
        if(!available(c,"SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE='CREATE TABLE'"))values.add("CREATE TABLE");
        if(!available(c,"SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE='UNLIMITED TABLESPACE'")&&!available(c,"SELECT COUNT(*) FROM USER_TS_QUOTAS WHERE TABLESPACE_NAME='DATA' AND (MAX_BYTES=-1 OR MAX_BYTES>BYTES)"))values.add("DATA quota");
        return List.copyOf(values);
    }
    public static List<String> adminSql(String owner,List<String> missing,long usedBytes){
        var sql=new ArrayList<String>();String target=MetadataSql.identifier(owner);
        if(missing.contains("CREATE TABLE"))sql.add("GRANT CREATE TABLE TO "+target+";");
        if(missing.contains("DATA quota"))sql.add("ALTER USER "+target+" QUOTA "+(Math.max(0,usedBytes)/1048576+101)+"M ON DATA;");
        return List.copyOf(sql);
    }
    public Status status(PoolSession s){return run(s,c->{
        var found=objects(c);var missing=missing(c);long used=0;
        if(missing.contains("DATA quota"))try(var q=c.createStatement()){q.setQueryTimeout(15);try(var r=q.executeQuery("SELECT NVL(MAX(BYTES),0) FROM USER_TS_QUOTAS WHERE TABLESPACE_NAME='DATA'")){if(r.next())used=r.getLong(1);}}
        return new Status(owner(s),found,missing,adminSql(owner(s),missing,used),found.isEmpty()&&missing.isEmpty());
    });}
    public Plan preview(PoolSession s){var state=status(s);if(!state.objects().isEmpty())throw error(409,"exists");if(!state.missing().isEmpty())throw error(403,"privileges");return new Plan(UUID.randomUUID().toString(),state.owner(),statements(),Instant.now().plusSeconds(300));}
    public static void require(Plan p,String token,boolean confirmed,String owner){if(p==null||!confirmed||!p.token().equals(token)||!p.owner().equals(owner)||!Instant.now().isBefore(p.expires()))throw error(409,"expired");}
    public Result install(PoolSession s,Plan plan){return run(s,c->{
        if(!owner(s).equals(plan.owner())||!objects(c).isEmpty())throw error(409,"exists");
        if(!missing(c).isEmpty())throw error(403,"privileges");
        try{
            c.setAutoCommit(false);
            for(var sql:plan.statements())try(var q=c.createStatement()){q.setQueryTimeout(30);q.execute(sql);}
            c.commit();return verify(c,owner(s));
        }catch(SQLException ex){c.rollback();throw ex;}
    });}
    public Result verify(PoolSession s){return run(s,c->verify(c,owner(s)));}
    private Result verify(Connection c,String owner)throws SQLException{try(var q=c.createStatement()){q.setQueryTimeout(15);try(var r=q.executeQuery("SELECT (SELECT COUNT(*) FROM DEPT),(SELECT COUNT(*) FROM EMP),(SELECT SUM(SAL) FROM EMP),(SELECT SUM(SAL+NVL(COMM,0)) FROM EMP) FROM DUAL")){if(!r.next())throw new SQLException("Missing result");long departments=r.getLong(1),employees=r.getLong(2);var salary=r.getBigDecimal(3);var compensation=r.getBigDecimal(4);return new Result(owner,departments,employees,salary,compensation,departments==4&&employees==14&&salary!=null&&salary.compareTo(new java.math.BigDecimal("29025"))==0&&compensation!=null&&compensation.compareTo(new java.math.BigDecimal("31225"))==0);}}}
}
