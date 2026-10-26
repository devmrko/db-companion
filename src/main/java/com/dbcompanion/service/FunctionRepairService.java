package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.MetadataSql;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiCreation;
import com.dbcompanion.model.FunctionCatalog.Failure;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Explicit owner-local replacement; never invokes the function or changes grants. */
@Service
public class FunctionRepairService {
    public record Source(String name,long objectId,String source,String status,List<String> errors) {}
    public record Plan(String token,String owner,Source before,String sql,Instant expires) {}
    public static Failure error(int status,String key){return new Failure(status,UiMessages.text("functionRepair."+key,key));}
    public static String owner(PoolSession session){
        String owner=session.metadata().info().username();
        if(!owner.equals(session.metadata().selectedSchema()))throw error(403,"scope");
        return owner;
    }
    public static String sql(String owner,String name,String source){
        AiCreation.name(name);
        if(source==null||source.isBlank()||source.length()>60000||source.indexOf(0)>=0||source.lines().anyMatch(s->s.trim().equals("/")))throw error(400,"invalid");
        var header=Pattern.compile("(?is)^\\s*FUNCTION\\s+(?:"+Pattern.quote(MetadataSql.identifier(name))+"|"+Pattern.quote(name)+")\\s*(?=\\(|RETURN\\b)").matcher(source);
        if(!header.find())throw error(400,"invalid");
        return "CREATE OR REPLACE FUNCTION "+MetadataSql.identifier(owner)+"."+MetadataSql.identifier(name)+" "+source.substring(header.end());
    }
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T run(PoolSession session,Work<T> work){synchronized(session){String owner=owner(session);try(var c=session.pool().getConnection()){c.setSchema(owner);return work.run(c);}catch(SQLException ex){throw new Failure(503,error(503,"failed").getMessage()+" (ORA-"+String.format(Locale.ROOT,"%05d",Math.abs(ex.getErrorCode()))+")");}}}
    private Source read(Connection c,String name)throws SQLException{
        AiCreation.name(name);long id;String status;
        try(var q=c.prepareStatement("SELECT OBJECT_ID,STATUS FROM USER_OBJECTS WHERE OBJECT_NAME=? AND OBJECT_TYPE='FUNCTION'")){q.setQueryTimeout(15);q.setString(1,name);try(var r=q.executeQuery()){if(!r.next())throw error(404,"missing");id=r.getLong(1);status=r.getString(2);}}
        var source=new StringBuilder();
        try(var q=c.prepareStatement("SELECT TEXT FROM USER_SOURCE WHERE NAME=? AND TYPE='FUNCTION' ORDER BY LINE")){q.setQueryTimeout(15);q.setString(1,name);try(var r=q.executeQuery()){while(r.next()){source.append(r.getString(1));if(source.length()>60000)throw error(400,"invalid");}}}
        var errors=new ArrayList<String>();
        try(var q=c.prepareStatement("SELECT LINE,POSITION,TEXT FROM USER_ERRORS WHERE NAME=? AND TYPE='FUNCTION' ORDER BY SEQUENCE FETCH FIRST 50 ROWS ONLY")){q.setQueryTimeout(15);q.setString(1,name);try(var r=q.executeQuery()){while(r.next())errors.add(r.getInt(1)+":"+r.getInt(2)+" "+r.getString(3));}}
        return new Source(name,id,source.toString(),status,List.copyOf(errors));
    }
    public Source read(PoolSession session,String name){return run(session,c->read(c,name));}
    public static void unchanged(Source expected,Source actual){if(expected.objectId()!=actual.objectId()||!expected.name().equals(actual.name())||!expected.source().equals(actual.source()))throw error(409,"stale");}
    public Plan preview(PoolSession session,Source baseline,String edited){return run(session,c->{
        if(baseline==null)throw error(409,"stale");unchanged(baseline,read(c,baseline.name()));
        return new Plan(UUID.randomUUID().toString(),owner(session),baseline,sql(owner(session),baseline.name(),edited),Instant.now().plusSeconds(300));
    });}
    public static void require(Plan plan,String token,boolean consent,String owner){if(plan==null||!consent||!plan.token().equals(token)||!plan.owner().equals(owner)||!Instant.now().isBefore(plan.expires()))throw error(409,"stale");}
    public Source apply(PoolSession session,Plan plan){return run(session,c->{
        if(!owner(session).equals(plan.owner()))throw error(403,"scope");unchanged(plan.before(),read(c,plan.before().name()));
        try(var q=c.createStatement()){q.setQueryTimeout(45);q.execute(plan.sql());}catch(SQLException ex){if(ex.getErrorCode()!=24344)throw ex;}finally{session.metadata().invalidateFunctions(plan.owner());}
        return read(c,plan.before().name());
    });}
}
