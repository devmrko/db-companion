package com.dbcompanion.model;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Deliberately bounded editor: local roles and one-role SELECT grants on owned tables. */
public final class DdsManagement {
    private DdsManagement() {}
    public record Draft(String schema,String kind,String action,String name,String table,String role,
                        String column,String valueType,String value,boolean enabled,String mapping) {
        public Draft(String schema,String kind,String action,String name,String table,String role,String column,String valueType,String value,boolean enabled){this(schema,kind,action,name,table,role,column,valueType,value,enabled,"");}
    }
    public record State(List<Map<String,String>> roles,List<Map<String,String>> grants,
                        List<Map<String,String>> columns,Set<String> privileges) {}
    public record Preview(String token,String confirmation,String sql,String revision,Draft draft,Draft previous) {}
    public record Apply(String token,String confirmation,boolean consent) {}
    public record PreviewRequest(Draft draft,String revision) {}
    public static final class Failure extends RuntimeException {
        public Failure(String key){super(key);}
    }
    public static void require(boolean ok,String key){if(!ok)throw new Failure(key);}
    public static String id(String value){require(value!=null&&value.matches("[A-Z][A-Z0-9_]{0,127}"),"invalid");return "\""+value+"\"";}
    public static String literal(String value){require(value!=null&&value.length()<=500&&value.codePoints().noneMatch(Character::isISOControl),"invalid");return "'"+value.replace("'","''")+"'";}
    public static String predicate(Draft d){
        String col=id(d.column());
        if("NUMBER".equals(d.valueType())){
            require(d.value()!=null&&d.value().matches("-?[0-9]{1,30}(\\.[0-9]{1,8})?"),"invalid");
            return col+" = "+new BigDecimal(d.value()).stripTrailingZeros().toPlainString();
        }
        require("TEXT".equals(d.valueType())&&d.value()!=null&&!d.value().isEmpty(),"invalid");
        return col+" = "+literal(d.value());
    }
    public static List<Map<String,String>> target(Draft d,State s){return (d.kind().equals("ROLE")?s.roles():s.grants()).stream().filter(r->Objects.equals(r.get(d.kind().equals("ROLE")?"DATA_ROLE":"GRANT_NAME"),d.name())).toList();}
    public static Draft previous(Draft d,State state){
        var rows=target(d,state);require(rows.size()==1,"unsupported");var r=rows.getFirst();
        if(d.kind().equals("ROLE")){
            require(r.keySet().equals(Set.of("DATA_ROLE","MAPPED_TO","ENABLED_BY_DEFAULT")),"unsupported");
            String mapped=Objects.toString(r.get("MAPPED_TO"),"");
            if(blank(mapped))require(Set.of("TRUE","FALSE","1","0").contains(Objects.toString(r.get("ENABLED_BY_DEFAULT"),"").toUpperCase(Locale.ROOT)),"unsupported");else mapping(mapped);
            return new Draft(d.schema(),"ROLE","UPDATE",d.name(),"","","","","",truth(r.get("ENABLED_BY_DEFAULT")),mapped);
        }
        var expected=new HashSet<>(Set.of("OWNER","GRANT_NAME","PRIVILEGE","COLUMN_NAME","GRANTED_WITH_ALL_COLUMNS_EXCEPT","OBJECT_OWNER","OBJECT_NAME","OBJECT_TYPE","PREDICATE","GRANTEE","GRANTEE_TYPE","USE_DATA_GRANTS_ONLY","START_TIME","END_TIME","INVALID_COLUMN_NAME"));
        var keys=new HashSet<>(r.keySet());keys.remove("CROSS_TABLE_DATA_GRANT");
        keys.remove("GRANT_TYPE");keys.remove("TAG_NAME");
        require((!r.containsKey("GRANT_TYPE")||"DATA GRANT".equals(r.get("GRANT_TYPE")))&&blank(r.get("TAG_NAME")),"unsupported");
        require(keys.equals(expected)&&!truth(r.get("CROSS_TABLE_DATA_GRANT"))&&!truth(r.get("USE_DATA_GRANTS_ONLY")),"unsupported");
        require(d.schema().equals(r.get("OWNER"))&&d.schema().equals(r.get("OBJECT_OWNER"))&&"TABLE".equals(r.get("OBJECT_TYPE"))&&"SELECT".equals(r.get("PRIVILEGE"))&&"DATA ROLE".equals(r.get("GRANTEE_TYPE")),"unsupported");
        for(String key:List.of("COLUMN_NAME","GRANTED_WITH_ALL_COLUMNS_EXCEPT","START_TIME","END_TIME","INVALID_COLUMN_NAME"))require(blank(r.get(key)),"unsupported");
        var matcher=Pattern.compile("^\\s*\"?([A-Z][A-Z0-9_]{0,127})\"?\\s*=\\s*(-?[0-9]+(?:\\.[0-9]+)?|'(?:[^']|'')*')\\s*$").matcher(Objects.toString(r.get("PREDICATE"),""));
        require(matcher.matches(),"unsupported");String value=matcher.group(2);boolean text=value.startsWith("'");
        return new Draft(d.schema(),"GRANT","UPDATE",d.name(),r.get("OBJECT_NAME"),r.get("GRANTEE"),matcher.group(1),text?"TEXT":"NUMBER",text?value.substring(1,value.length()-1).replace("''","'"):value,false);
    }
    public static boolean truth(String value){return "TRUE".equalsIgnoreCase(value)||"1".equals(value);}
    private static boolean blank(String value){return value==null||value.isEmpty();}
    public static String mapping(String value){
        if(blank(value))return "";
        require(value.length()<=500&&value.codePoints().noneMatch(Character::isISOControl),"mappingInvalid");
        require(value.startsWith("IAM_OAUTH_GROUP=")||value.startsWith("IAM_GROUP_NAME="),"mappingInvalid");
        String group=value.substring(value.indexOf('=')+1);
        require(!group.isBlank()&&group.equals(group.strip()),"mappingInvalid");
        if(value.startsWith("IAM_OAUTH_GROUP="))require(!group.contains("/"),"mappingInvalid");
        return value;
    }
    public static String sql(Draft d,State state,String username){
        require(d!=null&&Objects.equals(d.schema(),username),"ownSchema");id(d.schema());id(d.name());
        require(Set.of("ROLE","GRANT").contains(d.kind())&&Set.of("CREATE","UPDATE").contains(d.action()),"invalid");
        var existing=target(d,state);boolean update=d.action().equals("UPDATE");
        require(update?!existing.isEmpty():existing.isEmpty(),update?"missing":"exists");
        Draft old=update?previous(d,state):null;
        if(d.kind().equals("ROLE")){
            require("ADMIN".equals(username),"adminRole");require(state.privileges().contains("CREATE DATA ROLE"),"rolePrivilege");
            String mapped=mapping(d.mapping());
            require(old==null||blank(old.mapping())==blank(mapped),"roleType");
            require(blank(mapped)||state.roles().stream().noneMatch(r->!d.name().equals(r.get("DATA_ROLE"))&&mapped.equalsIgnoreCase(Objects.toString(r.get("MAPPED_TO"),""))),"mappingExists");
            return "CREATE "+(update?"OR REPLACE ":"")+"DATA ROLE "+id(d.name())+(blank(mapped)?(d.enabled()?" ENABLED":" DISABLED"):" MAPPED TO "+literal(mapped));
        }
        require(state.privileges().contains("CREATE DATA GRANT"),"grantPrivilege");
        require(state.privileges().contains("ADMINISTER ANY DATA GRANT"),"policyPrivilege");
        require(state.roles().stream().anyMatch(r->Objects.equals(r.get("DATA_ROLE"),d.role())),"localRole");
        require(old==null||Objects.equals(old.table(),d.table()),"unsupported");
        require(state.columns().stream().anyMatch(r->Objects.equals(r.get("TABLE_NAME"),d.table())&&Objects.equals(r.get("COLUMN_NAME"),d.column())&&("NUMBER".equals(d.valueType())?"NUMBER".equals(r.get("DATA_TYPE")):Set.of("VARCHAR2","CHAR","NVARCHAR2","NCHAR").contains(r.get("DATA_TYPE")))),"columnType");
        return "CREATE "+(update?"OR REPLACE ":"")+"DATA GRANT "+id(d.schema())+"."+id(d.name())+" AS SELECT ON "+id(d.schema())+"."+id(d.table())+" WHERE "+predicate(d)+" TO "+id(d.role());
    }
    public static void verify(Draft d,State after){
        Draft actual=previous(d,after);
        require(d.kind().equals("ROLE")?(blank(d.mapping())?blank(actual.mapping())&&actual.enabled()==d.enabled():Objects.equals(actual.mapping(),d.mapping())):Objects.equals(actual.table(),d.table())&&Objects.equals(actual.role(),d.role())&&predicate(actual).equals(predicate(d)),"verifyFailed");
    }
}
