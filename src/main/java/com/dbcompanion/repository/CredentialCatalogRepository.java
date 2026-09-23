package com.dbcompanion.repository;

import com.dbcompanion.model.CredentialCatalog;
import com.dbcompanion.model.CredentialCatalog.*;
import com.dbcompanion.model.AgentCatalog.Kind;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Fixed public dictionaries. Never returns credential payloads, Tool JSON or passwords. */
@Repository
public class CredentialCatalogRepository {
    public static final int LIMIT=5000;
    private final JdbcTemplate jdbc;
    public CredentialCatalogRepository(JdbcTemplate jdbc){
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);
    }
    public static String listSql(boolean own,boolean visible){
        String view=own?"SYS.USER_CREDENTIALS":visible?"SYS.ALL_CREDENTIALS":"SYS.DBA_CREDENTIALS";
        return "SELECT "+(own?"SYS_CONTEXT('USERENV','SESSION_USER')":"OWNER")+", CREDENTIAL_NAME, ENABLED, "
                +"CASE WHEN USERNAME IS NULL THEN 'EMPTY' WHEN USERNAME IS JSON THEN 'JSON' "
                +"WHEN USERNAME LIKE 'ocid1.user.%' THEN 'OCI_USER_OCID' ELSE 'TEXT' END, "
                +"CASE WHEN COMMENTS IS NULL THEN 'EMPTY' WHEN COMMENTS IS JSON THEN 'JSON' ELSE 'TEXT' END "
                +"FROM "+view+(own?"":" WHERE OWNER = ?")+" ORDER BY CREDENTIAL_NAME";
    }
    public Catalog list(String schema,boolean own){
        Catalog first=catalog(schema,own,false);
        return !own&&first.status().equals("ACCESS_REQUIRED")?catalog(schema,false,true):first;
    }
    private Catalog catalog(String schema,boolean own,boolean visible){
        String source=own?"SYS.USER_CREDENTIALS":visible?"SYS.ALL_CREDENTIALS":"SYS.DBA_CREDENTIALS";
        try {
            var items=rows(listSql(own,visible),(r,i)->new Entry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),own?new Object[]{}:new Object[]{schema});
            return new Catalog(items,source,"AVAILABLE","",Instant.now().toString());
        }catch(RuntimeException ex){return new Catalog(List.of(),source,status(ex),error(ex),Instant.now().toString());}
    }
    public Detail detail(Entry credential,boolean own){
        String prefix=own?"USER_":"DBA_",schema=credential.owner();
        var parts=new ArrayList<Section>();
        String profiles=prefix+"CLOUD_AI_PROFILE_ATTRIBUTES";
        parts.add(section("profiles",profiles,()->{
            String sql="SELECT PROFILE_NAME, DBMS_LOB.SUBSTR(ATTRIBUTE_VALUE,600,1), 'credential_name' FROM "+profiles
                    +" WHERE ATTRIBUTE_NAME='credential_name'"+(own?"":" AND OWNER = ?")+" ORDER BY PROFILE_NAME";
            return references(sql,credential,own?new Object[]{}:new Object[]{schema});
        }));
        String tools=prefix+"AI_AGENT_TOOL_ATTRIBUTES",names=prefix+"AI_AGENT_TOOLS";
        parts.add(section("tools",tools,()->{
            var attrs=columns(tools);var master=columns(names);
            String id=required(attrs,Kind.TOOL.idColumns()),otherId=required(master,Kind.TOOL.idColumns());
            String toolName=required(master,Kind.TOOL.nameColumns());
            String sql="SELECT t."+toolName+", CASE WHEN a.ATTRIBUTE_NAME='credential_name' "
                    +"THEN DBMS_LOB.SUBSTR(a.ATTRIBUTE_VALUE,600,1) ELSE JSON_VALUE(a.ATTRIBUTE_VALUE, "
                    +"'$.credential_name' RETURNING VARCHAR2(600) NULL ON ERROR) END, "
                    +"CASE WHEN a.ATTRIBUTE_NAME='credential_name' THEN 'credential_name' ELSE 'tool_params.credential_name' END FROM "+tools+" a JOIN "+names+" t ON a."+id+"=t."+otherId
                    +(own?"":" AND a.OWNER=t.OWNER")+" WHERE a.ATTRIBUTE_NAME IN ('credential_name','tool_params')"
                    +(own?"":" AND a.OWNER = ?")+" ORDER BY t."+toolName;
            return references(sql,credential,own?new Object[]{}:new Object[]{schema});
        }));
        parts.add(dictionarySection("links","DB_LINKS",(view)->
                "SELECT OWNER, DB_LINK, 'credential_name' FROM "+view+" WHERE OWNER = ? AND CREDENTIAL_OWNER = ? AND CREDENTIAL_NAME = ? ORDER BY DB_LINK",
                schema,schema,credential.name()));
        parts.add(dictionarySection("jobs","SCHEDULER_JOBS",(view)->
                "SELECT OWNER, JOB_NAME, CASE WHEN CREDENTIAL_OWNER = ? AND CREDENTIAL_NAME = ? THEN 'credential_name' ELSE 'connect_credential_name' END FROM "+view
                        +" WHERE OWNER = ? AND ((CREDENTIAL_OWNER = ? AND CREDENTIAL_NAME = ?) OR (CONNECT_CREDENTIAL_OWNER = ? AND CONNECT_CREDENTIAL_NAME = ?)) ORDER BY JOB_NAME",
                schema,credential.name(),schema,schema,credential.name(),schema,credential.name()));
        return new Detail(credential,parts);
    }
    private record Candidate(String name,String credential,String field) {}
    private List<Use> references(String sql,Entry target,Object... args){
        return rows(sql,(r,i)->new Candidate(r.getString(1),r.getString(2),r.getString(3)),args).stream()
                .filter(u->CredentialCatalog.matches(u.credential(),target.owner(),target.owner(),target.name()))
                .map(u->new Use(target.owner(),u.name(),u.field())).distinct().toList();
    }
    private Section dictionarySection(String kind,String suffix,java.util.function.Function<String,String> sql,Object... args){
        String view="SYS.DBA_"+suffix;
        Section first=section(kind,view,()->rows(sql.apply(view),(r,i)->new Use(r.getString(1),r.getString(2),r.getString(3)),args));
        if(!first.status().equals("ACCESS_REQUIRED"))return first;
        String visible="SYS.ALL_"+suffix;
        return section(kind,visible,()->rows(sql.apply(visible),(r,i)->new Use(r.getString(1),r.getString(2),r.getString(3)),args));
    }
    private String required(AgentViewColumns columns,String... names){
        String column=columns.optional(names);if(column==null)throw new UnsupportedCatalog();return column;
    }
    private Section section(String kind,String source,Supplier<List<Use>> read){
        try{return new Section(kind,read.get(),source,"AVAILABLE","");}
        catch(RuntimeException ex){return new Section(kind,List.of(),source,status(ex),error(ex));}
    }
    private AgentViewColumns columns(String view){
        // Caller supplies only fixed Tool view names; no data is returned by this metadata query.
        return jdbc.query("SELECT * FROM "+view+" WHERE 1=0",r->{
            var names=new ArrayList<String>();var meta=r.getMetaData();
            for(int i=1;i<=meta.getColumnCount();i++)names.add(meta.getColumnLabel(i));
            return new AgentViewColumns(names);
        });
    }
    private <T>List<T> rows(String sql,RowMapper<T> mapper,Object... args){
        var values=jdbc.query(sql+" FETCH FIRST "+(LIMIT+1)+" ROWS ONLY",mapper,args);
        if(values.size()>LIMIT)throw new LimitExceeded();
        return List.copyOf(values);
    }
    public static int oracleCode(Throwable error){
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        var queue=new ArrayDeque<Throwable>();queue.add(error);
        while(!queue.isEmpty()&&seen.size()<32){
            var item=queue.removeFirst();if(!seen.add(item))continue;
            if(item instanceof SQLException sql){if(sql.getErrorCode()!=0)return sql.getErrorCode();if(sql.getNextException()!=null)queue.add(sql.getNextException());}
            if(item.getCause()!=null)queue.add(item.getCause());
        }
        return 0;
    }
    public static String status(Throwable error){
        if(error instanceof LimitExceeded)return "LIMIT";
        if(error instanceof UnsupportedCatalog)return "UNSUPPORTED";
        return switch(oracleCode(error)){case 942,1031->"ACCESS_REQUIRED";case 904->"UNSUPPORTED";default->"ERROR";};
    }
    public static String error(Throwable error){int code=oracleCode(error);return code==0?"":String.format(Locale.ROOT,"ORA-%05d",code);}
    private static final class LimitExceeded extends RuntimeException {}
    private static final class UnsupportedCatalog extends RuntimeException {}
}
