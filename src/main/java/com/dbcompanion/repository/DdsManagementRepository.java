package com.dbcompanion.repository;

import com.dbcompanion.model.DdsManagement.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;

@Repository
public class DdsManagementRepository {
    private final JdbcTemplate jdbc;
    public DdsManagementRepository(JdbcTemplate template){jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(15);}
    private List<Map<String,String>> rows(String sql,Object... args){
        return jdbc.query(sql,ps->{for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);},(rs,n)->{
            if(n>=5000)throw new Failure("limit");var row=new TreeMap<String,String>();var meta=rs.getMetaData();
            for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rs.getString(i));return Collections.unmodifiableMap(row);
        });
    }
    public State state(String owner){
        var roles=rows("SELECT * FROM SYS.DBA_DATA_ROLES ORDER BY DATA_ROLE FETCH FIRST 5001 ROWS ONLY");
        var grants=rows("SELECT * FROM SYS.ALL_DATA_GRANTS WHERE OWNER = ? ORDER BY GRANT_NAME, PRIVILEGE, COLUMN_NAME, GRANTEE FETCH FIRST 5001 ROWS ONLY",owner);
        var columns=rows("SELECT c.TABLE_NAME,c.COLUMN_NAME,c.DATA_TYPE FROM ALL_TAB_COLUMNS c JOIN ALL_TABLES t ON t.OWNER=c.OWNER AND t.TABLE_NAME=c.TABLE_NAME WHERE c.OWNER=? AND t.NESTED='NO' AND t.SECONDARY='N' ORDER BY c.TABLE_NAME,c.COLUMN_ID FETCH FIRST 5001 ROWS ONLY",owner);
        var privileges=new TreeSet<>(jdbc.queryForList("SELECT PRIVILEGE FROM SESSION_PRIVS",String.class));
        privileges.addAll(jdbc.queryForList("SELECT PRIVILEGE FROM SESSION_SCHEMA_PRIVS WHERE SCHEMA = ?",String.class,owner));
        return new State(roles,grants,columns,privileges);
    }
    public void execute(String sql){jdbc.execute((ConnectionCallback<Void>)c->{try(var statement=c.createStatement()){statement.setQueryTimeout(30);statement.execute(sql);}return null;});}
}
