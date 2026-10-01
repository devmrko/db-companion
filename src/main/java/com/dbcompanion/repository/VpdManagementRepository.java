package com.dbcompanion.repository;

import com.dbcompanion.model.VpdManagement;
import com.dbcompanion.model.VpdManagement.*;
import java.util.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VpdManagementRepository {
    private static final int LIMIT=5000;
    private static final Set<String> POLICY_FIELDS=Set.of("OBJECT_OWNER","OBJECT_NAME","POLICY_GROUP","POLICY_NAME","PF_OWNER","PACKAGE","FUNCTION","SEL","INS","UPD","DEL","IDX","CHK_OPTION","ENABLE","STATIC_POLICY","POLICY_TYPE","LONG_PREDICATE","COMMON","INHERITED");
    private static final Set<String> COLUMN_FIELDS=Set.of("OBJECT_OWNER","OBJECT_NAME","POLICY_GROUP","POLICY_NAME","SEC_REL_COLUMN","COLUMN_OPTION","COMMON","INHERITED");
    private final JdbcTemplate jdbc;
    public VpdManagementRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<String> tables(String schema){return objects(schema).stream().map(Target::name).toList();}
    public List<Target> objects(String schema){
        var objects=rows("""
            SELECT OBJECT_NAME, OBJECT_TYPE,
                   CASE WHEN OWNER = SYS_CONTEXT('USERENV','SESSION_USER') THEN 'Y' ELSE 'N' END OWNED
            FROM SYS.ALL_OBJECTS
            WHERE OWNER = ? AND OBJECT_TYPE IN ('TABLE','VIEW') AND SUBOBJECT_NAME IS NULL
            ORDER BY OBJECT_NAME, OBJECT_TYPE
            """,schema);
        var grants=rows("""
            SELECT TABLE_NAME,
                   LISTAGG(DISTINCT PRIVILEGE, ',') WITHIN GROUP (ORDER BY PRIVILEGE) PRIVILEGES,
                   MAX(CASE WHEN GRANTEE = SYS_CONTEXT('USERENV','SESSION_USER') THEN 1 ELSE 0 END) DIRECT_GRANT,
                   MAX(CASE WHEN GRANTEE = 'PUBLIC' THEN 1 ELSE 0 END) PUBLIC_GRANT,
                   MAX(CASE WHEN GRANTEE NOT IN (SYS_CONTEXT('USERENV','SESSION_USER'),'PUBLIC') THEN 1 ELSE 0 END) ROLE_GRANT
            FROM SYS.ALL_TAB_PRIVS
            WHERE TABLE_SCHEMA = ?
              AND (GRANTEE IN (SYS_CONTEXT('USERENV','SESSION_USER'),'PUBLIC')
                   OR GRANTEE IN (SELECT ROLE FROM SYS.SESSION_ROLES))
            GROUP BY TABLE_NAME ORDER BY TABLE_NAME
            """,schema);
        var byName=new HashMap<String,Map<String,String>>();
        for(var grant:grants)byName.put(grant.get("TABLE_NAME"),grant);
        return objects.stream().map(object->{
            var grant=byName.getOrDefault(object.get("OBJECT_NAME"),Map.of());
            String privileges=grant.getOrDefault("PRIVILEGES","");
            var sources=new ArrayList<String>();
            for(String source:List.of("DIRECT","ROLE","PUBLIC"))if("1".equals(grant.get(source+"_GRANT")))sources.add(source);
            return new Target(object.get("OBJECT_NAME"),object.get("OBJECT_TYPE"),"Y".equals(object.get("OWNED")),
                privileges.isEmpty()?List.of():List.of(privileges.split(",")),List.copyOf(sources));
        }).toList();
    }
    public Snapshot snapshot(String schema,String table){
        var objects=rows("SELECT OBJECT_ID, OBJECT_TYPE, LAST_DDL_TIME, STATUS FROM SYS.ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME = ? AND OBJECT_TYPE IN ('TABLE','VIEW') AND SUBOBJECT_NAME IS NULL",schema,table);
        if(objects.size()!=1)throw new Failure(404,"vpd.tableMissing");
        return new Snapshot(objects,
            rows("SELECT * FROM SYS.ALL_POLICIES WHERE OBJECT_OWNER = ? AND OBJECT_NAME = ? ORDER BY POLICY_GROUP, POLICY_NAME",schema,table),
            rows("SELECT COLUMN_NAME, COLUMN_ID, DATA_TYPE FROM SYS.ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID",schema,table),
            rows("SELECT * FROM SYS.ALL_SEC_RELEVANT_COLS WHERE OBJECT_OWNER = ? AND OBJECT_NAME = ? ORDER BY POLICY_GROUP, POLICY_NAME, SEC_REL_COLUMN",schema,table),
            rows("SELECT * FROM SYS.ALL_POLICY_ATTRIBUTES WHERE OBJECT_OWNER = ? AND OBJECT_NAME = ? ORDER BY POLICY_GROUP, POLICY_NAME, NAMESPACE, ATTRIBUTE",schema,table));
    }
    public boolean canManage(){
        return !rows("""
            SELECT GRANTEE FROM SYS.ALL_TAB_PRIVS
            WHERE TABLE_SCHEMA = 'SYS' AND TABLE_NAME = 'DBMS_RLS' AND PRIVILEGE = 'EXECUTE'
              AND (GRANTEE IN (SYS_CONTEXT('USERENV','SESSION_USER'), 'PUBLIC')
                   OR GRANTEE IN (SELECT ROLE FROM SYS.SESSION_ROLES))
            """).isEmpty();
    }
    public List<Function> functions(String owner){
        return rows("""
            SELECT a.OWNER, a.PACKAGE_NAME, a.OBJECT_NAME, a.SUBPROGRAM_ID,
                   o.OBJECT_ID, TO_CHAR(o.LAST_DDL_TIME, 'YYYYMMDDHH24MISS') DDL_TIME,
                   b.OBJECT_ID BODY_ID, TO_CHAR(b.LAST_DDL_TIME, 'YYYYMMDDHH24MISS') BODY_DDL_TIME
            FROM SYS.ALL_ARGUMENTS a
            JOIN SYS.ALL_OBJECTS o ON o.OWNER = a.OWNER
              AND o.OBJECT_NAME = COALESCE(a.PACKAGE_NAME, a.OBJECT_NAME)
              AND o.OBJECT_TYPE = CASE WHEN a.PACKAGE_NAME IS NULL THEN 'FUNCTION' ELSE 'PACKAGE' END
              AND o.STATUS = 'VALID'
            LEFT JOIN SYS.ALL_OBJECTS b ON b.OWNER = a.OWNER AND b.OBJECT_NAME = a.PACKAGE_NAME
              AND b.OBJECT_TYPE = 'PACKAGE BODY' AND b.STATUS = 'VALID'
            WHERE a.OWNER = ? AND a.OVERLOAD IS NULL AND a.DATA_LEVEL = 0
              AND (a.PACKAGE_NAME IS NULL OR b.OBJECT_ID IS NOT NULL)
            GROUP BY a.OWNER, a.PACKAGE_NAME, a.OBJECT_NAME, a.SUBPROGRAM_ID,
                     o.OBJECT_ID, o.LAST_DDL_TIME, b.OBJECT_ID, b.LAST_DDL_TIME
            HAVING COUNT(*) = 3
               AND SUM(CASE WHEN a.POSITION = 0 AND a.DATA_TYPE = 'VARCHAR2' THEN 1 ELSE 0 END) = 1
               AND SUM(CASE WHEN a.POSITION IN (1,2) AND a.IN_OUT = 'IN' AND a.DATA_TYPE = 'VARCHAR2' THEN 1 ELSE 0 END) = 2
            ORDER BY a.PACKAGE_NAME NULLS FIRST, a.OBJECT_NAME, a.SUBPROGRAM_ID
            """,owner).stream().map(row->new Function(row.get("OWNER"),
                row.get("PACKAGE_NAME").isEmpty()?row.get("OBJECT_NAME"):row.get("PACKAGE_NAME")+"."+row.get("OBJECT_NAME"),
                VpdManagement.digest(List.of(List.of(row))))).filter(f->simpleFunction(f.owner(),f.name())).toList();
    }
    private static boolean simpleFunction(String owner,String name){
        try{VpdManagement.identifier(owner);for(String part:name.split("\\."))VpdManagement.identifier(part);return true;}
        catch(IllegalArgumentException ex){return false;}
    }
    public static List<Policy> policies(Snapshot snapshot){
        return snapshot.policies().stream().map(row->policy(row,snapshot)).toList();
    }
    private static Policy policy(Map<String,String> row,Snapshot snapshot){
        String name=row.get("POLICY_NAME"),group=row.get("POLICY_GROUP");
        var relevant=snapshot.relevant().stream().filter(r->Objects.equals(name,r.get("POLICY_NAME"))&&Objects.equals(group,r.get("POLICY_GROUP"))).toList();
        var attributes=snapshot.attributes().stream().filter(r->Objects.equals(name,r.get("POLICY_NAME"))&&Objects.equals(group,r.get("POLICY_GROUP"))).toList();
        Draft definition=null;String reason="";
        try{
            if(!POLICY_FIELDS.equals(row.keySet())||!"SYS_DEFAULT".equals(group)||!local(row)||!attributes.isEmpty())throw new IllegalArgumentException("metadata");
            for(String field:List.of("SEL","INS","UPD","DEL","IDX","CHK_OPTION","ENABLE","STATIC_POLICY","LONG_PREDICATE"))
                if(!Set.of("YES","NO").contains(row.get(field)))throw new IllegalArgumentException("flag");
            for(var col:relevant)if(!COLUMN_FIELDS.equals(col.keySet())||!local(col)||!Set.of("NONE","ALL_ROWS").contains(col.get("COLUMN_OPTION")))throw new IllegalArgumentException("columns");
            if(relevant.stream().map(r->r.get("COLUMN_OPTION")).distinct().count()>1)throw new IllegalArgumentException("mixed columns");
            var statements=new ArrayList<String>();var flags=List.of("SEL","INS","UPD","DEL","IDX");
            for(int i=0;i<flags.size();i++)if("YES".equals(row.get(flags.get(i))))statements.add(VpdManagement.STATEMENTS.get(i));
            definition=new Draft(row.get("OBJECT_OWNER"),row.get("OBJECT_NAME"),name,row.get("PF_OWNER"),
                row.get("PACKAGE").isEmpty()?row.get("FUNCTION"):row.get("PACKAGE")+"."+row.get("FUNCTION"),statements,
                "YES".equals(row.get("CHK_OPTION")),"YES".equals(row.get("ENABLE")),PolicyType.valueOf(row.get("POLICY_TYPE")),
                "YES".equals(row.get("LONG_PREDICATE")),relevant.stream().map(r->r.get("SEC_REL_COLUMN")).toList(),
                relevant.stream().anyMatch(r->"ALL_ROWS".equals(r.get("COLUMN_OPTION")))).validated();
        }catch(IllegalArgumentException|NullPointerException ex){reason="vpd.unsupported";}
        return new Policy(group,name,definition,reason.isEmpty(),reason,row,relevant,attributes);
    }
    private static boolean local(Map<String,String> row){return "NO".equals(row.get("COMMON"))&&"NO".equals(row.get("INHERITED"));}
    public static Command add(Draft input){
        var d=input.validated();
        String sql="BEGIN SYS.DBMS_RLS.ADD_POLICY(object_schema => ?, object_name => ?, policy_name => ?, function_schema => ?, policy_function => ?, statement_types => ?, update_check => "+bool(d.updateCheck())+", enable => "+bool(d.enabled())+", policy_type => SYS.DBMS_RLS."+d.policyType()+", long_predicate => "+bool(d.longPredicate())+", sec_relevant_cols => ?, sec_relevant_cols_opt => "+(d.allRows()?"SYS.DBMS_RLS.ALL_ROWS":"NULL")+"); END;";
        return new Command(sql,Arrays.asList(d.schema(),d.table(),d.policy(),d.functionSchema(),d.function(),String.join(",",d.statements()),d.columns().isEmpty()?null:String.join(",",d.columns())));
    }
    public static Command drop(Draft d){return new Command("BEGIN SYS.DBMS_RLS.DROP_POLICY(object_schema => ?, object_name => ?, policy_name => ?); END;",List.of(d.schema(),d.table(),d.policy()));}
    public static Command enable(Draft d,boolean enabled){return new Command("BEGIN SYS.DBMS_RLS.ENABLE_POLICY(object_schema => ?, object_name => ?, policy_name => ?, enable => "+bool(enabled)+"); END;",List.of(d.schema(),d.table(),d.policy()));}
    private static String bool(boolean value){return value?"TRUE":"FALSE";}
    public void execute(List<Command> commands){
        jdbc.execute((ConnectionCallback<Void>)connection->{
            for(var command:commands){
                try(var call=connection.prepareCall(command.sql())){
                    call.setQueryTimeout(30);for(int i=0;i<command.arguments().size();i++)call.setString(i+1,command.arguments().get(i));call.execute();
                }
            }
            return null;
        });
    }
    private List<Map<String,String>> rows(String sql,String... args){
        return jdbc.query(sql+" FETCH FIRST "+(LIMIT+1)+" ROWS ONLY",statement->{statement.setQueryTimeout(15);for(int i=0;i<args.length;i++)statement.setString(i+1,args[i]);},result->{
            var rows=new ArrayList<Map<String,String>>();var metadata=result.getMetaData();
            while(result.next()){
                if(rows.size()==LIMIT)throw new Failure(409,"vpd.limit");
                var row=new LinkedHashMap<String,String>();for(int i=1;i<=metadata.getColumnCount();i++)row.put(metadata.getColumnLabel(i),Objects.toString(result.getString(i),""));
                rows.add(Collections.unmodifiableMap(row));
            }
            return List.copyOf(rows);
        });
    }
}
