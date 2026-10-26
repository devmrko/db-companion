package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.AiCreation;
import com.dbcompanion.model.LocalObjectCreation.*;
import com.dbcompanion.model.LocalObjectCreation;
import com.dbcompanion.common.exception.ObjectCreationException;
import java.sql.*;
import java.util.ArrayList;
import org.springframework.stereotype.Service;

/** One owner-local CREATE per consumed plan; no replace, grants, execution or automatic retry. */
@Service
public class LocalObjectCreationService {
    private final SessionDataSource source;
    public LocalObjectCreationService(SessionDataSource source){this.source=source;}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T bound(PoolSession session,Input in,boolean writes,Work<T> work){
        synchronized(session){
            AiCreation.scope(session.metadata().info().username(),session.metadata().selectedSchema(),in.schema());
            source.bind(session.pool(),in.schema());
            boolean completed=false;
            try(var c=source.getConnection()){var result=work.run(c);completed=true;return result;}
            catch(SQLException ex){throw ObjectCreationException.from(ex,completed&&writes?"VERIFY":"PREFLIGHT",completed&&writes);}
            finally{source.clear();}
        }
    }
    private int count(Connection c,String sql,String value)throws SQLException{
        try(var s=c.prepareStatement(sql)){s.setQueryTimeout(15);s.setString(1,value);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Count result missing");return r.getInt(1);}}
    }
    private void preflight(Connection c,Input in)throws SQLException{
        String sql=in.kind()==Kind.CREDENTIAL?"SELECT COUNT(*) FROM USER_CREDENTIALS WHERE CREDENTIAL_NAME=?":"SELECT COUNT(*) FROM USER_OBJECTS WHERE OBJECT_NAME=?";
        if(count(c,sql,in.name())!=0)throw AiCreation.error(409,"exists");
        if(in.kind()==Kind.FUNCTION&&count(c,"SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE=?","CREATE PROCEDURE")==0)throw new SQLException("CREATE PROCEDURE required","42000",1031);
    }
    private String cloud(Connection c)throws SQLException{
        try(var s=c.createStatement()){s.setQueryTimeout(15);try(var r=s.executeQuery("SELECT TABLE_OWNER,TABLE_NAME FROM ALL_SYNONYMS WHERE OWNER='PUBLIC' AND SYNONYM_NAME='DBMS_CLOUD' AND DB_LINK IS NULL")){
            if(!r.next())throw new IllegalArgumentException("DBMS_CLOUD not available");
            return ProfileHistorySql.object(r.getString(1),r.getString(2));
        }}
    }
    public Plan preview(PoolSession session,Input in){
        LocalObjectCreation.validate(in);
        return bound(session,in,false,c->{preflight(c,in);return LocalObjectCreation.plan(in,in.kind()==Kind.FUNCTION?LocalObjectCreation.functionSql(in):
                "BEGIN\n  "+cloud(c)+".CREATE_CREDENTIAL(\n    credential_name => :name,\n    username => :username,\n    password => :secret\n  );\nEND;");});
    }
    public Result create(PoolSession session,Plan plan,String secret){
        var in=plan.input();
        return bound(session,in,true,c->{
            preflight(c,in);
            boolean submitted=false;String stage="PREFLIGHT";
            try{
            if(in.kind()==Kind.CREDENTIAL){
                try(var s=c.prepareCall("BEGIN "+cloud(c)+".CREATE_CREDENTIAL(credential_name=>?,username=>?,password=>?); END;")){
                    s.setQueryTimeout(45);s.setString(1,in.name());s.setString(2,in.username());s.setString(3,secret);submitted=true;stage="CREATE";s.execute();
                }
                stage="VERIFY";
                boolean exists=count(c,"SELECT COUNT(*) FROM USER_CREDENTIALS WHERE CREDENTIAL_NAME=?",in.name())==1;
                return new Result(in.name(),exists?"CREATED":"UNCONFIRMED",exists,java.util.List.of());
            }
            try(var s=c.createStatement()){s.setQueryTimeout(45);submitted=true;stage="CREATE";s.execute(plan.sql());}
            catch(SQLException ex){if(ex.getErrorCode()!=24344)throw ex;}
            stage="VERIFY";
            String status="UNCONFIRMED";
            try(var s=c.prepareStatement("SELECT STATUS FROM USER_OBJECTS WHERE OBJECT_NAME=? AND OBJECT_TYPE='FUNCTION'")){
                s.setQueryTimeout(15);s.setString(1,in.name());try(var r=s.executeQuery()){if(r.next())status=r.getString(1);}
            }
            var errors=new ArrayList<String>();
            try(var s=c.prepareStatement("SELECT LINE,POSITION,TEXT FROM USER_ERRORS WHERE NAME=? AND TYPE='FUNCTION' ORDER BY SEQUENCE FETCH FIRST 50 ROWS ONLY")){
                s.setQueryTimeout(15);s.setString(1,in.name());try(var r=s.executeQuery()){while(r.next())errors.add(r.getInt(1)+":"+r.getInt(2)+" "+r.getString(3));}
            }
            return new Result(in.name(),status,"VALID".equals(status),errors);
            }catch(SQLException ex){throw ObjectCreationException.from(ex,stage,submitted);}
        });
    }
}
