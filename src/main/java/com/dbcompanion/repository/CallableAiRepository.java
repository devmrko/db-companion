package com.dbcompanion.repository;

import com.dbcompanion.common.config.SelectAiExecutionSettings;
import com.dbcompanion.common.db.CallableAiSql;
import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.model.CallableAi.*;
import com.dbcompanion.service.OntologyQueryService;
import java.io.StringReader;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class CallableAiRepository {
    private final JdbcTemplate jdbc;private final JsonMapper json;private final SelectAiExecutionSettings settings;
    public CallableAiRepository(JdbcTemplate template,JsonMapper json,SelectAiExecutionSettings settings){
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(30);this.json=json;this.settings=settings;
    }
    public List<String> source(){return jdbc.queryForList("SELECT TEXT FROM USER_SOURCE WHERE NAME=? AND TYPE IN ('PACKAGE','PACKAGE BODY') ORDER BY TYPE,LINE",String.class,CallableAiSql.NAME);}
    public List<String> users(){return jdbc.queryForList("SELECT USERNAME FROM SYS.ALL_USERS WHERE ORACLE_MAINTAINED='N' AND USERNAME<>'ADMIN' ORDER BY USERNAME",String.class);}
    public Access access(String username){
        if(username==null||!users().contains(username))throw new IllegalArgumentException("Select a local application user");
        return new Access(username,jdbc.queryForObject("SELECT COUNT(*) FROM SYS.DBA_SYS_PRIVS WHERE GRANTEE=? AND PRIVILEGE='CREATE PROCEDURE'",Integer.class,username)>0);
    }
    public static String grantSql(String username){return "GRANT CREATE PROCEDURE TO "+com.dbcompanion.common.db.MetadataSql.identifier(username);}
    public Access grant(String username){if(access(username).granted())throw new IllegalArgumentException("Privilege already granted");jdbc.execute(grantSql(username));return access(username);}
    public String fingerprint(){return OntologyQueryService.hash(String.join("",source()));}
    private boolean expected(String owner,String type,String expected){
        String actual=String.join("",jdbc.queryForList("SELECT TEXT FROM USER_SOURCE WHERE NAME=? AND TYPE=? ORDER BY LINE",String.class,CallableAiSql.NAME,type));
        String prefix=type.equals("PACKAGE")?"CREATE PACKAGE ":"CREATE PACKAGE BODY ";
        String normalized=expected.substring(prefix.length()).replaceFirst(java.util.regex.Pattern.quote(ProfileHistorySql.object(owner,CallableAiSql.NAME)),CallableAiSql.NAME);
        String wanted=(type+" "+normalized).replace("\r","").strip();
        // Oracle keeps quoted or unquoted package identifiers in USER_SOURCE.
        return actual.replace("\r","").replaceFirst("^"+type+"\\s+",type+" ").replace("\""+CallableAiSql.NAME+"\"",CallableAiSql.NAME).strip().equals(wanted);
    }
    public String state(String owner){
        var objects=jdbc.queryForList("SELECT OBJECT_TYPE FROM USER_OBJECTS WHERE OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL ORDER BY OBJECT_TYPE",String.class,CallableAiSql.NAME);
        if(objects.isEmpty())return "MISSING";
        var ddl=CallableAiSql.statements(owner);
        if(!objects.contains("PACKAGE")||objects.stream().anyMatch(t->!List.of("PACKAGE","PACKAGE BODY").contains(t))||!expected(owner,"PACKAGE",ddl.getFirst()))return "CONFLICT";
        if(!objects.contains("PACKAGE BODY"))return "INCOMPLETE";
        if(!expected(owner,"PACKAGE BODY",ddl.get(1)))return "CONFLICT";
        return jdbc.queryForObject("SELECT COUNT(*) FROM USER_OBJECTS WHERE OBJECT_NAME=? AND STATUS<>'VALID'",Integer.class,CallableAiSql.NAME)==0?"READY":"INVALID";
    }
    public Status status(String owner){
        String state=state(owner);
        var errors=jdbc.query("SELECT TYPE,LINE,POSITION,TEXT FROM USER_ERRORS WHERE NAME=? ORDER BY TYPE,SEQUENCE",(r,n)->r.getString(1)+":"+r.getInt(2)+":"+r.getInt(3)+" "+r.getString(4),CallableAiSql.NAME);
        var profiles=jdbc.queryForList("SELECT PROFILE_NAME FROM USER_CLOUD_AI_PROFILES WHERE STATUS='ENABLED' ORDER BY PROFILE_NAME",String.class);
        var tables=new ArrayList<String>();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='DBC_ONTOLOGY_CATALOG'",Integer.class)>0){
            tables.addAll(jdbc.queryForList("SELECT OBJECT_NAME FROM (SELECT OBJECT_NAME,STATE,ROW_NUMBER() OVER(PARTITION BY OBJECT_OWNER,OBJECT_NAME ORDER BY REVISION DESC) RN FROM DBC_ONTOLOGY_CATALOG WHERE OBJECT_OWNER=?) WHERE RN=1 AND STATE='APPROVED' ORDER BY OBJECT_NAME",String.class,owner));
        }
        return new Status(owner,state,errors,profiles,tables);
    }
    public void install(String owner){
        String state=state(owner);var statements=CallableAiSql.statements(owner);
        if(!List.of("MISSING","INCOMPLETE").contains(state))throw new IllegalArgumentException("Package already exists or differs");
        if(state.equals("MISSING"))jdbc.execute(statements.getFirst());
        jdbc.execute(statements.get(1));
    }
    public JsonNode run(String owner,Request request){
        if(!state(owner).equals("READY"))throw new IllegalArgumentException("Package is not ready");
        int seconds=request.mode().equals("CONTEXT")?30:settings.generateTimeoutSeconds()+(request.mode().equals("QUERY")?settings.sqlTimeoutSeconds():0);
        String sql="BEGIN ? := "+ProfileHistorySql.object(owner,CallableAiSql.NAME)+".ASK(?,?,?,?,?,?,?); END;";
        return jdbc.execute((ConnectionCallback<JsonNode>)connection->{
            int network=connection.getNetworkTimeout();boolean changed=network>0&&network<(seconds+30)*1000;
            try {
                if(changed)connection.setNetworkTimeout(Runnable::run,(seconds+30)*1000);
                try(var call=connection.prepareCall(sql);var question=new StringReader(request.question());var tables=new StringReader(json.writeValueAsString(request.tables()))){
                    call.setQueryTimeout(seconds);call.registerOutParameter(1,Types.CLOB);call.setCharacterStream(2,question);
                    call.setString(3,request.profile());call.setInt(4,request.glossary()?1:0);call.setInt(5,request.ontology()?1:0);
                    call.setCharacterStream(6,tables);call.setString(7,request.mode());call.setInt(8,request.maxRows());
                    call.execute();var result=call.getClob(1);
                    if(result==null)throw new IllegalArgumentException("No callable response");
                    try {if(result.length()>1_200_000)throw new IllegalArgumentException("Response too large");return json.readTree(result.getSubString(1,(int)result.length()));}
                    finally {result.free();}
                }
            }finally {if(changed)connection.setNetworkTimeout(Runnable::run,network);}
        });
    }
}
