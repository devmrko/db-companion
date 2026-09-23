package com.dbcompanion.repository;

import com.dbcompanion.service.SelectAiReadSql;
import com.dbcompanion.service.SqlObjectName;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SelectAiExecutionRepository {
    private final JdbcTemplate jdbc;
    public SelectAiExecutionRepository(JdbcTemplate source){jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(10);}
    public record ObjectRef(String owner,String name,String type,String link) {}
    public static boolean permittedDependency(ObjectRef ref){return ref.link()==null&&Set.of("TABLE","VIEW","MATERIALIZED VIEW","SYNONYM").contains(ref.type());}
    public void verify(SelectAiReadSql.Checked sql,String owner){
        // Oracle permits some zero-argument routine references without (). Conservatively reject
        // collisions with schema routines or synonyms, even if a token could be a column/alias.
        if(!sql.identifiers().isEmpty()){
            String marks=String.join(",",Collections.nCopies(sql.identifiers().size(),"?"));
            var args=new ArrayList<Object>();args.add(owner);args.addAll(sql.identifiers());
            var routineArgs=new ArrayList<Object>();routineArgs.add(owner);routineArgs.addAll(sql.identifiers());routineArgs.addAll(sql.identifiers());
            Integer routines=jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_OBJECTS WHERE (OWNER=? OR OWNER IN ("+marks+")) AND OBJECT_TYPE IN ('FUNCTION','PACKAGE','TYPE') AND OBJECT_NAME IN ("+marks+")",Integer.class,routineArgs.toArray());
            if(routines!=null&&routines>0)throw SelectAiReadSql.blocked();
            // Synonym targets are verified below, including callable targets.
            var synonyms=jdbc.query("SELECT TABLE_OWNER,TABLE_NAME,DB_LINK FROM SYS.ALL_SYNONYMS WHERE OWNER IN (?, 'PUBLIC') AND SYNONYM_NAME IN ("+marks+")",
                    (r,n)->new ObjectRef(r.getString(1),r.getString(2),"SYNONYM",r.getString(3)),args.toArray());
            var checked=new HashSet<String>();for(var synonym:synonyms){if(synonym.link()!=null)throw SelectAiReadSql.blocked();verifyObject(synonym.owner(),synonym.name(),checked,0);}
        }
        var checked=new HashSet<String>();for(String table:sql.tables()){
            var parts=SqlObjectName.parts(table);verifyObject(parts.size()==1?owner:parts.getFirst(),parts.getLast(),checked,0);
        }
    }
    private void verifyObject(String owner,String name,Set<String> seen,int depth){
        if(depth>12||seen.size()>100)throw SelectAiReadSql.blocked();String key=owner+"\0"+name;if(!seen.add(key))return;
        if(owner.equals("SYS")&&name.equals("DUAL"))return;
        var types=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL",String.class,owner,name);
        if(types.contains("MATERIALIZED VIEW"))types=List.of("MATERIALIZED VIEW");
        if(types.isEmpty()||types.equals(List.of("SYNONYM"))){
            var synonyms=jdbc.query("SELECT TABLE_OWNER,TABLE_NAME,DB_LINK,OWNER FROM SYS.ALL_SYNONYMS WHERE OWNER IN (?, 'PUBLIC') AND SYNONYM_NAME=? ORDER BY CASE WHEN OWNER=? THEN 0 ELSE 1 END",
                    (r,n)->new ObjectRef(r.getString(1),r.getString(2),"SYNONYM",r.getString(3)),owner,name,owner);
            if(synonyms.isEmpty()||synonyms.getFirst().link()!=null)throw SelectAiReadSql.blocked();var target=synonyms.getFirst();verifyObject(target.owner(),target.name(),seen,depth+1);return;
        }
        if(types.size()!=1||!Set.of("TABLE","VIEW","MATERIALIZED VIEW").contains(types.getFirst()))throw SelectAiReadSql.blocked();
        if(!types.getFirst().equals("VIEW")){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_EXTERNAL_TABLES WHERE OWNER=? AND TABLE_NAME=?",Integer.class,owner,name)!=0
                    ||jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TAB_COLS WHERE OWNER=? AND TABLE_NAME=? AND (VIRTUAL_COLUMN='YES' OR DATA_TYPE_OWNER IS NOT NULL)",Integer.class,owner,name)!=0)throw SelectAiReadSql.blocked();
            return;
        }
        // Remote view dependencies can be incomplete in ALL_DEPENDENCIES. Inspect the full
        // definition too; TEXT_VC may truncate, so read LONG TEXT after checking its length.
        var definitions=jdbc.query("SELECT TEXT_LENGTH,TEXT FROM SYS.ALL_VIEWS WHERE OWNER=? AND VIEW_NAME=?",(r,n)->{
            if(r.getInt(1)>20_000)throw SelectAiReadSql.blocked();String text=r.getString(2);
            if(text==null||text.isBlank())throw SelectAiReadSql.blocked();return SelectAiReadSql.check(text);
        },owner,name);
        if(definitions.size()!=1)throw SelectAiReadSql.blocked();
        var dependencies=jdbc.query("SELECT REFERENCED_OWNER,REFERENCED_NAME,REFERENCED_TYPE,REFERENCED_LINK_NAME FROM SYS.ALL_DEPENDENCIES WHERE OWNER=? AND NAME=? AND TYPE='VIEW'",
                (r,n)->new ObjectRef(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),owner,name);
        // Missing dependency metadata cannot prove a view is local/read-only.
        if(dependencies.isEmpty())throw SelectAiReadSql.blocked();
        for(var dependency:dependencies){if(!permittedDependency(dependency))throw SelectAiReadSql.blocked();verifyObject(dependency.owner(),dependency.name(),seen,depth+1);}
        for(String table:definitions.getFirst().tables()){
            var parts=SqlObjectName.parts(table);verifyObject(parts.size()==1?owner:parts.getFirst(),parts.getLast(),seen,depth+1);
        }
    }
}
