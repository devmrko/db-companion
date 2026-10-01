package com.dbcompanion.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/** Complete supported policy definition; dictionary values are never executed as SQL identifiers. */
public final class VpdManagement {
    private VpdManagement() {}
    public enum Action { ADD, REPLACE, DELETE, ENABLE, DISABLE }
    public enum PolicyType { DYNAMIC, STATIC, SHARED_STATIC, CONTEXT_SENSITIVE, SHARED_CONTEXT_SENSITIVE }
    public static final List<String> STATEMENTS=List.of("SELECT","INSERT","UPDATE","DELETE","INDEX");
    public record Draft(String schema,String table,String policy,String functionSchema,String function,
            List<String> statements,boolean updateCheck,boolean enabled,PolicyType policyType,
            boolean longPredicate,List<String> columns,boolean allRows) {
        public Draft validated(){
            identifier(schema);identifier(table);identifier(policy);identifier(functionSchema);
            if(function==null)throw new IllegalArgumentException("function");
            var parts=function.split("\\.",-1);if(parts.length>2)throw new IllegalArgumentException("function");
            for(String part:parts)identifier(part);
            if(statements==null||statements.isEmpty()||statements.size()>5||!STATEMENTS.containsAll(statements)
                    ||new HashSet<>(statements).size()!=statements.size()||policyType==null)throw new IllegalArgumentException("statements");
            if(statements.contains("INSERT")&&!updateCheck)throw new IllegalArgumentException("INSERT requires update_check");
            if(updateCheck&&!statements.contains("INSERT")&&!statements.contains("UPDATE"))throw new IllegalArgumentException("update_check");
            var names=columns==null?List.<String>of():columns;
            if(names.size()>1000||new HashSet<>(names).size()!=names.size())throw new IllegalArgumentException("columns");
            names.forEach(VpdManagement::identifier);
            if(String.join(",",names).length()>4000)throw new IllegalArgumentException("columns");
            if(allRows&&(names.isEmpty()||!statements.equals(List.of("SELECT"))))throw new IllegalArgumentException("ALL_ROWS requires SELECT and columns");
            return new Draft(schema,table,policy,functionSchema,function,STATEMENTS.stream().filter(statements::contains).toList(),
                updateCheck,enabled,policyType,longPredicate,names.stream().sorted().toList(),allRows);
        }
        public Draft withEnabled(boolean value){return new Draft(schema,table,policy,functionSchema,function,statements,updateCheck,value,policyType,longPredicate,columns,allRows);}
    }
    public record Snapshot(List<Map<String,String>> objects,List<Map<String,String>> policies,
            List<Map<String,String>> columns,List<Map<String,String>> relevant,List<Map<String,String>> attributes) {
        public String fingerprint(){return digest(List.of(objects,policies,columns,relevant,attributes));}
    }
    public record Policy(String group,String name,Draft definition,boolean editable,String reason,
            Map<String,String> properties,List<Map<String,String>> relevant,List<Map<String,String>> attributes) {}
    public record Catalog(String status,String error,List<Policy> policies,List<String> columns,boolean writable,String reason,String fingerprint) {}
    /** Visible object grants, not a complete effective-access or policy-management decision. */
    public record Target(String name,String type,boolean owned,List<String> privileges,List<String> grantSources) {}
    public record Function(String owner,String name,String identity) {}
    public record Command(String sql,List<String> arguments) {
        public String preview(){
            var out=new StringBuilder();int index=0;
            for(char c:sql.toCharArray()){
                if(c=='?'){String value=arguments.get(index++);out.append(value==null?"NULL":"'"+value.replace("'","''")+"'");}
                else out.append(c);
            }
            return out+"\n/";
        }
    }
    public record Preview(String token,Action action,String target,String sql,String recoverySql,boolean gap,String expiresAt) {}
    public record Pending(Preview preview,Draft draft,String fingerprint,String functionIdentity,Instant expiresAt,List<Command> commands) {}
    public record Receipt(String status,String message,String recoverySql) {}
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status,String key){super(key);this.status=status;}
        public int status(){return status;}
    }
    public static void identifier(String value){
        if(value==null||!value.matches("[A-Z][A-Z0-9_$#]{0,127}"))throw new IllegalArgumentException("Unquoted uppercase identifier required");
    }
    public static String digest(List<List<Map<String,String>>> groups){
        try{
            var digest=MessageDigest.getInstance("SHA-256");
            for(var group:groups){update(digest,Integer.toString(group.size()));for(var row:group){update(digest,Integer.toString(row.size()));
                new TreeMap<>(row).forEach((key,value)->{update(digest,key);update(digest,value);});}}
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static void update(MessageDigest digest,String value){
        byte[] bytes=Objects.toString(value,"").getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length+":").getBytes(StandardCharsets.UTF_8));digest.update(bytes);
    }
}
