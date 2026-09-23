package com.dbcompanion.repository;

import com.dbcompanion.common.db.OracleErrorDetails;
import com.dbcompanion.model.DeepDataSecurity.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only fixed dictionary views. No DDL, privilege changes, context attachment or business data. */
@Repository
public class DeepDataSecurityRepository {
    public static final int ROW_LIMIT=5000;
    private final JdbcTemplate jdbc;
    public DeepDataSecurityRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private enum View {
        ROLES("DBA_DATA_ROLES"), ASSIGNMENTS("DBA_DATA_ROLE_GRANTS"), APPLICATIONS("DBA_APPLICATION_IDENTITIES"),
        GRANTS("DBA_DATA_GRANTS"), VISIBLE_GRANTS("ALL_DATA_GRANTS"),
        PARENTS("DBA_REQUIRED_PARENT_DATA_PRIVILEGES"), VISIBLE_PARENTS("ALL_REQUIRED_PARENT_DATA_PRIVILEGES");
        final String name;View(String name){this.name="SYS."+name;}
    }
    public Dataset list(Kind kind,String schema){
        return switch(kind){
            case roles -> read(View.ROLES,"*"," ORDER BY DATA_ROLE");
            case assignments -> read(View.ASSIGNMENTS,"*"," ORDER BY DATA_ROLE, GRANTEE_TYPE, GRANTEE, ROLE_TYPE, START_TIME, END_TIME");
            case applications -> read(View.APPLICATIONS,"*"," ORDER BY APPLICATION_NAME");
            case grants -> grants("DISTINCT GRANT_NAME, OWNER, OBJECT_OWNER, OBJECT_NAME, OBJECT_TYPE",
                    " WHERE OBJECT_OWNER = ? ORDER BY OWNER, GRANT_NAME, OBJECT_NAME",schema);
        };
    }
    public Detail detail(Kind kind,String schema,String name,String owner){
        var sections=new ArrayList<Section>();
        switch(kind){
            case roles -> {
                sections.add(new Section("properties",read(View.ROLES,"*"," WHERE DATA_ROLE = ?",name)));
                sections.add(new Section("assignments",read(View.ASSIGNMENTS,"*","""
                         WHERE (DATA_ROLE = ? AND ROLE_TYPE = 'DATA ROLE')
                            OR (GRANTEE = ? AND GRANTEE_TYPE = 'DATA ROLE')
                         ORDER BY DATA_ROLE, GRANTEE_TYPE, GRANTEE, ROLE_TYPE, START_TIME, END_TIME
                        """,name,name)));
                sections.add(new Section("grants",grants("*","""
                         WHERE OBJECT_OWNER = ? AND GRANTEE = ? AND GRANTEE_TYPE = 'DATA ROLE'
                         ORDER BY OWNER, GRANT_NAME, PRIVILEGE, COLUMN_NAME
                        """,schema,name)));
            }
            case applications -> {
                sections.add(new Section("properties",read(View.APPLICATIONS,"*"," WHERE APPLICATION_NAME = ?",name)));
                sections.add(new Section("assignments",read(View.ASSIGNMENTS,"*","""
                         WHERE GRANTEE = ? AND GRANTEE_TYPE = 'APPLICATION IDENTITY'
                         ORDER BY DATA_ROLE, ROLE_TYPE, START_TIME, END_TIME
                        """,name)));
            }
            case grants -> {
                Dataset data=grants("*"," WHERE OBJECT_OWNER = ? AND OWNER = ? AND GRANT_NAME = ? ORDER BY PRIVILEGE, COLUMN_NAME, GRANTEE",schema,owner,name);
                sections.add(new Section("grants",data));
                if(data.rows().stream().anyMatch(row->isTrue(row.get("CROSS_TABLE_DATA_GRANT")))){
                    View parent=data.source().equals(View.GRANTS.name)?View.PARENTS:View.VISIBLE_PARENTS;
                    sections.add(new Section("parents",read(parent,"*"," WHERE OWNER = ? AND GRANT_NAME = ? ORDER BY PRIVILEGE, COLUMN_NAME",owner,name)));
                }
            }
            case assignments -> throw new IllegalArgumentException("Assignment values are already in the list");
        }
        return new Detail(sections);
    }
    public static boolean isTrue(String value){return "true".equalsIgnoreCase(value)||"1".equals(value);}
    private Dataset grants(String projection,String tail,Object... args){
        Dataset result=read(View.GRANTS,projection,tail,args);
        return result.status().equals("ACCESS_REQUIRED")?read(View.VISIBLE_GRANTS,projection,tail,args):result;
    }
    private Dataset read(View view,String projection,String tail,Object... args){
        String observed=Instant.now().toString();
        try {
            return jdbc.query("SELECT "+projection+" FROM "+view.name+tail+" FETCH FIRST "+(ROW_LIMIT+1)+" ROWS ONLY",
                    statement->{for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);}, result->{
                var columns=new ArrayList<String>();var metadata=result.getMetaData();
                for(int i=1;i<=metadata.getColumnCount();i++)columns.add(metadata.getColumnLabel(i));
                var rows=new ArrayList<Map<String,String>>();
                while(result.next()){
                    var row=new LinkedHashMap<String,String>();
                    for(int i=0;i<columns.size();i++)row.put(columns.get(i),result.getString(i+1));
                    rows.add(row);
                    if(rows.size()>ROW_LIMIT)return new Dataset(view.name,List.of(),List.of(),"LIMIT","",observed);
                }
                return new Dataset(view.name,columns,rows,"AVAILABLE","",observed);
            });
        } catch(RuntimeException ex){
            return new Dataset(view.name,List.of(),List.of(),accessError(ex)?"ACCESS_REQUIRED":"ERROR",OracleErrorDetails.forDisplay(ex),observed);
        }
    }
    public static boolean accessError(Throwable error){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        var queue=new ArrayDeque<Throwable>();queue.add(error);
        while(!queue.isEmpty()&&seen.size()<32){
            var item=queue.removeFirst();if(!seen.add(item))continue;
            if(item instanceof SQLException sql){
                if(sql.getErrorCode()==942||sql.getErrorCode()==1031)return true;
                if(sql.getNextException()!=null)queue.add(sql.getNextException());
            }
            if(item.getCause()!=null)queue.add(item.getCause());
        }
        return false;
    }
}
