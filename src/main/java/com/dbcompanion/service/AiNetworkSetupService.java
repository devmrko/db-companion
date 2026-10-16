package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiAssistant;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Explicit ADMIN-only outbound HTTP grants to fixed AI provider hosts. */
@Service
public class AiNetworkSetupService {
    private static final Map<String,String> HOSTS=Map.of("OPENAI","api.openai.com","COHERE","api.cohere.ai","ANTHROPIC","api.anthropic.com","GOOGLE","generativelanguage.googleapis.com");
    public record Input(String name,String provider) {}
    public record Ace(Integer lowerPort,Integer upperPort,int order,String grantType,String inverted,String principalType,String privilege,String start,String end) {}
    public record State(String name,String host,long userId,List<Ace> entries,boolean directGrant) {}
    public record Plan(String token,Input input,State before,String sql,Instant expires) {}
    public record Result(String status,State state) {}
    public static AiAssistant.Failure error(int status,String key){return new AiAssistant.Failure(status,"aiNetwork."+key,UiMessages.text("aiNetwork."+key,key));}
    public static String host(Input input){
        if(input==null||input.name()==null||input.provider()==null||!HOSTS.containsKey(input.provider()))throw error(400,"invalid");
        if(!input.name().matches("[A-Z][A-Z0-9_]{2,29}")||Set.of("SYS","SYSTEM","PUBLIC","GUEST","ANONYMOUS").contains(input.name()))throw error(400,"invalid");
        return HOSTS.get(input.provider());
    }
    public static String sql(Input input){return "BEGIN\n  DBMS_NETWORK_ACL_ADMIN.APPEND_HOST_ACE(\n    host => '"+host(input)+"',\n    ace => XS$ACE_TYPE(privilege_list => XS$NAME_LIST('http'),\n      principal_name => '"+input.name()+"', principal_type => XS_ACL.PTYPE_DB));\nEND;";}
    public static boolean directGrant(List<Ace> entries){return entries.stream().anyMatch(a->a.lowerPort()==null&&a.upperPort()==null&&"GRANT".equals(a.grantType())&&"NO".equals(a.inverted())&&"DB".equals(a.principalType())&&"http".equalsIgnoreCase(a.privilege())&&a.start()==null&&a.end()==null);}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T run(PoolSession s,Work<T> work){AccountSetupService.admin(s);synchronized(s){try(var c=s.pool().getConnection()){return work.run(c);}catch(SQLException ex){throw error(503,"failed");}}}
    private State read(Connection c,Input input)throws SQLException{
        String host=host(input);long id;
        try(var q=c.prepareStatement("SELECT USER_ID FROM DBA_USERS WHERE USERNAME=? AND (USERNAME='ADMIN' OR (ORACLE_MAINTAINED='N' AND COMMON='NO'))")){
            q.setQueryTimeout(15);q.setString(1,input.name());try(var r=q.executeQuery()){if(!r.next())throw error(409,"identity");id=r.getLong(1);}
        }
        var entries=new ArrayList<Ace>();
        try(var q=c.prepareStatement("SELECT LOWER_PORT,UPPER_PORT,ACE_ORDER,GRANT_TYPE,INVERTED_PRINCIPAL,PRINCIPAL_TYPE,PRIVILEGE,START_DATE,END_DATE FROM DBA_HOST_ACES WHERE LOWER(HOST)=? AND PRINCIPAL=? ORDER BY LOWER_PORT NULLS FIRST,UPPER_PORT NULLS FIRST,ACE_ORDER,PRIVILEGE")){
            q.setQueryTimeout(15);q.setString(1,host);q.setString(2,input.name());try(var r=q.executeQuery()){while(r.next()){if(entries.size()>=1000)throw error(409,"changed");entries.add(new Ace(r.getObject(1,Integer.class),r.getObject(2,Integer.class),r.getInt(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9)));}}
        }
        return new State(input.name(),host,id,List.copyOf(entries),directGrant(entries));
    }
    public State status(PoolSession s,Input input){host(input);return run(s,c->read(c,input));}
    public Plan preview(PoolSession s,Input input){String sql=sql(input);return run(s,c->{var before=read(c,input);if(before.directGrant())throw error(409,"exists");return new Plan(UUID.randomUUID().toString(),input,before,sql,Instant.now().plusSeconds(300));});}
    public Result apply(PoolSession s,Plan plan){return run(s,c->{
        if(!Instant.now().isBefore(plan.expires())||!plan.sql().equals(sql(plan.input()))||!plan.before().equals(read(c,plan.input())))throw error(409,"changed");
        try(var q=c.prepareCall("BEGIN DBMS_NETWORK_ACL_ADMIN.APPEND_HOST_ACE(host => ?, ace => XS$ACE_TYPE(privilege_list => XS$NAME_LIST('http'), principal_name => ?, principal_type => XS_ACL.PTYPE_DB)); END;")){
            q.setQueryTimeout(30);q.setString(1,plan.before().host());q.setString(2,plan.input().name());q.execute();
        }
        c.commit();var after=read(c,plan.input());if(!after.directGrant())throw error(503,"failed");return new Result("VERIFIED",after);
    });}
}
