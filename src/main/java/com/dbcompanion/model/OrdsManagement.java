package com.dbcompanion.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Current-login-schema ORDS metadata, never database schema DDL. */
public final class OrdsManagement {
    private OrdsManagement() {}
    public enum Kind {SCHEMA, MODULE, TEMPLATE, HANDLER}
    public enum Action {CREATE, UPDATE, DELETE, PUBLISH}
    public record Input(String schema, Kind kind, Action action, String module, String pattern,
                        String method, String revision, Map<String,String> values) {}
    public record Apply(String schema, String token, String confirmation) {}
    public record Statement(String api, String sql, List<String> bindings) {}
    public record Snapshot(String status, String detail, Set<String> apis,
                           Map<String,List<Map<String,String>>> tables, String revision) {
        public List<Map<String,String>> rows(String table) {return tables.getOrDefault(table,List.of());}
    }
    public record Preview(String token, String confirmation, String revision, Input input,
                          Map<String,String> original, Map<String,String> proposed,
                          Map<String,Integer> affected, List<Statement> statements, boolean destructive) {}
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status,String key) {super(key);this.status=status;}
        public int status() {return status;}
    }
    public static String digest(Object value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new JsonMapper().writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    public static void require(boolean condition, String key) {if(!condition)throw new Failure(409,key);}
    public static String field(Map<String,String> values,String key,boolean required,int max) {
        String value=values.get(key);
        if(value==null)value="";
        if((required&&value.isBlank())||value.indexOf('\0')>=0||value.getBytes(StandardCharsets.UTF_8).length>max)
            throw new Failure(400,"invalid");
        for(int i=0;i<value.length();i++)if(Character.isSurrogate(value.charAt(i))&&
            (!Character.isHighSurrogate(value.charAt(i))||i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i))))throw new Failure(400,"invalid");
        return value;
    }
}
