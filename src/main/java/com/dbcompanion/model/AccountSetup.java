package com.dbcompanion.model;

import com.dbcompanion.common.i18n.UiMessages;
import java.time.Instant;
import java.util.*;

/** Fixed owner-local privilege presets; no roles, ANY privileges or network ACL changes. */
public final class AccountSetup {
    private AccountSetup() {}
    public enum Preset { LIMITED, WORKSPACE, AI_WORKSPACE }
    public record Input(String name,Preset preset) {}
    public record Apply(String token,boolean confirmed,String password) {
        @Override public String toString(){return "Apply[password=REDACTED]";}
    }
    public record Plan(String token,Input input,List<String> statements,Instant expires) {}
    public record Status(String name,boolean exists,String accountStatus,List<String> privileges,List<String> packages,long quotaBytes) {}
    public static AiAssistant.Failure error(int status,String key){return new AiAssistant.Failure(status,"accountSetup."+key,UiMessages.text("accountSetup."+key,key));}
    public static void validate(Input input){
        if(input==null||input.preset()==null||input.name()==null||!input.name().matches("[A-Z][A-Z0-9_]{2,29}")||Set.of("ADMIN","SYS","SYSTEM","PUBLIC","GUEST","ANONYMOUS").contains(input.name()))throw error(400,"invalid");
    }
    public static void password(String value){
        if(value==null||!value.matches("[A-Za-z0-9_#$!@%+=:.,?\\-]{12,30}")||!value.matches(".*[A-Z].*")||!value.matches(".*[a-z].*")||!value.matches(".*[0-9].*"))throw error(400,"passwordHelp");
    }
    public static List<String> privileges(Preset preset){return preset==Preset.LIMITED?List.of("CREATE SESSION"):preset==Preset.WORKSPACE?List.of("CREATE SESSION","CREATE TABLE","CREATE VIEW","CREATE PROCEDURE","CREATE SEQUENCE"):List.of("CREATE SESSION","CREATE TABLE","CREATE VIEW","CREATE PROCEDURE","CREATE SEQUENCE","CREATE JOB","CREATE PROPERTY GRAPH");}
    public static List<String> packages(Preset preset){return preset==Preset.AI_WORKSPACE?List.of("DBMS_CLOUD","DBMS_CLOUD_AI","DBMS_CLOUD_AI_AGENT"):List.of();}
    public static List<String> grants(Input input){
        validate(input);String name="\""+input.name()+"\"";var sql=new ArrayList<String>();
        for(var p:privileges(input.preset()))sql.add("GRANT "+p+" TO "+name);
        for(var p:packages(input.preset()))sql.add("GRANT EXECUTE ON "+p+" TO "+name);
        if(input.preset()!=Preset.LIMITED)sql.add("ALTER USER "+name+" QUOTA 100M ON DATA");
        return List.copyOf(sql);
    }
    public static Plan plan(Input input){
        validate(input);var statements=new ArrayList<String>();statements.add("CREATE USER \""+input.name()+"\" IDENTIFIED BY <password> DEFAULT TABLESPACE DATA");statements.addAll(grants(input));
        return new Plan(UUID.randomUUID().toString(),input,List.copyOf(statements),Instant.now().plusSeconds(300));
    }
    public static void require(Plan plan,Apply apply){if(plan==null||apply==null||!apply.confirmed()||!plan.token().equals(apply.token())||!Instant.now().isBefore(plan.expires()))throw error(409,"expired");}
}
