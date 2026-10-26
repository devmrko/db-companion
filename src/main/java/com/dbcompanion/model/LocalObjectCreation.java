package com.dbcompanion.model;

import com.dbcompanion.common.db.ProfileHistorySql;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A five-minute plan contains public input only. Credential secrets arrive only at apply. */
public final class LocalObjectCreation {
    private LocalObjectCreation() {}
    public enum Kind { CREDENTIAL, FUNCTION }
    public record Input(String schema,Kind kind,String name,String username,String body) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unknown field");}
    }
    public record Apply(String token,boolean consent,String secret) {
        @Override public String toString(){return "Apply[secret=REDACTED]";}
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unknown field");}
    }
    public record Plan(String token,Input input,String sql,Instant expires) {}
    public record Result(String name,String status,boolean verified,List<String> errors) {}
    public static void validate(Input in){
        if(in==null||in.kind()==null)throw new IllegalArgumentException("Object kind required");
        AiCreation.name(in.schema());AiCreation.name(in.name());
        if(in.kind()==Kind.CREDENTIAL){
            AiCreation.text(in.username(),1024);
            if(in.username().isBlank()||in.body()!=null&&!in.body().isEmpty())throw new IllegalArgumentException("Username required; credential code is fixed");
        }else{
            AiCreation.text(in.body(),60000);
            if(in.body().isBlank()||in.body().lines().anyMatch(s->s.trim().equals("/")))throw new IllegalArgumentException("Function body required; omit SQL client slash separators");
            if(in.username()!=null&&!in.username().isEmpty())throw new IllegalArgumentException("Function has no credential username");
        }
    }
    public static String functionSql(Input in){
        validate(in);
        if(in.kind()!=Kind.FUNCTION)throw new IllegalArgumentException("Function required");
        return "CREATE FUNCTION "+ProfileHistorySql.object(in.schema(),in.name())+"(p_input CLOB)\nRETURN CLOB AUTHID CURRENT_USER IS\n"+in.body();
    }
    public static Plan plan(Input in,String sql){return new Plan(UUID.randomUUID().toString(),in,sql,Instant.now().plusSeconds(300));}
    public static void require(Plan plan,Apply apply,String login,String selected){
        if(plan==null||!apply.consent()||!plan.token().equals(apply.token())||!Instant.now().isBefore(plan.expires()))throw new IllegalArgumentException("Preview expired or confirmation missing");
        AiCreation.scope(login,selected,plan.input().schema());
        if(plan.input().kind()==Kind.CREDENTIAL){AiCreation.text(apply.secret(),4000);if(apply.secret().isBlank())throw new IllegalArgumentException("Credential secret required");}
        else if(apply.secret()!=null&&!apply.secret().isEmpty())throw new IllegalArgumentException("Function does not accept credentials");
    }
}
