package com.dbcompanion.repository;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Fixed collector prerequisites, never arbitrary grants or privilege escalation. */
@Repository
public class SqlArchiveAccessRepository {
    private final JdbcTemplate jdbc;
    public SqlArchiveAccessRepository(JdbcTemplate template) {
        jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(15);
    }
    public record Access(String username,long userId,boolean read,boolean select,boolean createTable,boolean createJob) {
        public boolean ready(){return (read||select)&&createTable&&createJob;}
    }
    public void requireAdmin(){
        if(!"ADMIN".equals(jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV','SESSION_USER') FROM DUAL",String.class)))
            throw new SecurityException("ADMIN login required");
    }
    public List<String> users(){
        requireAdmin();
        return jdbc.queryForList("SELECT USERNAME FROM SYS.DBA_USERS WHERE ORACLE_MAINTAINED='N' AND USERNAME<>'ADMIN' ORDER BY USERNAME",String.class);
    }
    public Access inspect(String username){
        requireAdmin();quote(username);
        var ids=jdbc.queryForList("SELECT USER_ID FROM SYS.DBA_USERS WHERE USERNAME=? AND ORACLE_MAINTAINED='N' AND USERNAME<>'ADMIN'",Long.class,username);
        if(ids.size()!=1)throw new IllegalArgumentException("Choose an existing application user");
        var object=jdbc.queryForList("SELECT PRIVILEGE FROM SYS.DBA_TAB_PRIVS WHERE GRANTEE=? AND OWNER='SYS' AND TABLE_NAME='V_$SQL' AND PRIVILEGE IN ('READ','SELECT')",String.class,username);
        var system=jdbc.queryForList("SELECT PRIVILEGE FROM SYS.DBA_SYS_PRIVS WHERE GRANTEE=? AND PRIVILEGE IN ('CREATE TABLE','CREATE JOB')",String.class,username);
        return new Access(username,ids.getFirst(),object.contains("READ"),object.contains("SELECT"),system.contains("CREATE TABLE"),system.contains("CREATE JOB"));
    }
    public static String quote(String username){
        if(username==null||username.isBlank()||username.length()>128||username.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid username");
        return "\""+username.replace("\"","\"\"")+"\"";
    }
    public static List<String> plan(Access access){
        String user=quote(access.username());var sql=new ArrayList<String>();
        if(!access.read()&&!access.select())sql.add("GRANT READ ON SYS.V_$SQL TO "+user);
        if(!access.createTable())sql.add("GRANT CREATE TABLE TO "+user);
        if(!access.createJob())sql.add("GRANT CREATE JOB TO "+user);
        return List.copyOf(sql);
    }
    public Access apply(Access approved){
        var current=inspect(approved.username());
        if(!current.equals(approved))throw new IllegalArgumentException("Privileges or target changed; inspect again");
        for(var sql:plan(current))jdbc.execute(sql);
        var after=inspect(current.username());
        if(!after.ready())throw new IllegalStateException("Grant outcome requires verification");
        return after;
    }
}
