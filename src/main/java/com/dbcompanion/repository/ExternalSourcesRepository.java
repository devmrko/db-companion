package com.dbcompanion.repository;

import com.dbcompanion.common.db.ExternalAddress;
import com.dbcompanion.common.db.LinkEndpoint;
import com.dbcompanion.model.ExternalSources.*;
import com.dbcompanion.model.SchemaAcl;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ExternalSourcesRepository {
    private static final int LIMIT=5000;
    private final JdbcTemplate jdbc;
    public ExternalSourcesRepository(JdbcTemplate source){jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(10);}
    public static String listSql(Kind kind,boolean own,boolean fallback){
        String prefix=own?"USER":fallback?"ALL":"DBA";
        String owner=own?"SYS_CONTEXT('USERENV','SESSION_USER')":"OWNER";
        String filter=own?"":" WHERE OWNER = ?";
        if(kind==Kind.tables)return "SELECT "+owner+", TABLE_NAME, TYPE_NAME FROM SYS."+prefix+"_EXTERNAL_TABLES"+filter+" ORDER BY TABLE_NAME";
        String privateLinks="SELECT "+owner+" AS OWNER, DB_LINK, USERNAME FROM SYS."+prefix+"_DB_LINKS"+(own?"":" WHERE OWNER = ? AND OWNER <> 'PUBLIC'");
        return "SELECT OWNER, DB_LINK, USERNAME FROM ("+privateLinks+" UNION ALL SELECT OWNER, DB_LINK, USERNAME FROM SYS.ALL_DB_LINKS WHERE OWNER = 'PUBLIC') ORDER BY OWNER, DB_LINK";
    }
    public Catalog list(Kind kind,String schema,boolean own){
        Catalog result=readList(kind,schema,own,false);
        return !own&&result.status().equals("ACCESS_REQUIRED")?readList(kind,schema,false,true):result;
    }
    private Catalog readList(Kind kind,String schema,boolean own,boolean fallback){
        String source="SYS."+(own?"USER":fallback?"ALL":"DBA")+(kind==Kind.links?"_DB_LINKS + SYS.ALL_DB_LINKS (PUBLIC)":"_EXTERNAL_TABLES");
        try{return new Catalog(rows(listSql(kind,own,fallback),(r,n)->new Entry(r.getString(1),r.getString(2),r.getString(3)),own?new Object[]{}:new Object[]{schema}),source,"AVAILABLE","");}
        catch(RuntimeException ex){return new Catalog(List.of(),source,status(ex),error(ex));}
    }
    public Detail detail(Kind kind,Entry entry,boolean own){
        List<Field> fields;
        try{fields=fields(kind,entry,own,false);}
        catch(RuntimeException ex){
            if(!own&&status(ex).equals("ACCESS_REQUIRED"))fields=fields(kind,entry,false,true);else throw ex;
        }
        List<Section> sections=kind==Kind.links?List.of():List.of(locations(entry,own,"locations"),locations(entry,own,"partitions"),locations(entry,own,"subpartitions"));
        return new Detail(entry,fields,sections);
    }
    private List<Field> fields(Kind kind,Entry e,boolean own,boolean fallback){
        String prefix=e.owner().equals("PUBLIC")?"ALL":own?"USER":fallback?"ALL":"DBA";
        boolean user=prefix.equals("USER");String view="SYS."+prefix+(kind==Kind.links?"_DB_LINKS":"_EXTERNAL_TABLES");
        String projection;
        if(kind==Kind.links){var columns=columns(view);projection="USERNAME, HOST, CREATED, "+columns.projection("CREDENTIAL_OWNER")+", "+columns.projection("CREDENTIAL_NAME");}
        else projection="TYPE_NAME, DEFAULT_DIRECTORY_NAME, REJECT_LIMIT";
        String sql="SELECT "+projection+" FROM "+view+" WHERE "+(user?"":"OWNER = ? AND ")+(kind==Kind.links?"DB_LINK":"TABLE_NAME")+" = ?";
        List<List<Field>> result=jdbc.query(sql,(r,n)->{
            if(kind==Kind.links){
                var endpoint=LinkEndpoint.parse(r.getString(2));
                return List.of(new Field("username",r.getString(1)),new Field("endpoint",String.join(" · ",endpoint.endpoints())),new Field("service",endpoint.service()),new Field("alias",endpoint.alias()),new Field("endpointStatus",endpoint.status()),new Field("created",r.getString(3)),new Field("credentialOwner",r.getString(4)),new Field("credential",r.getString(5)));
            }
            return List.of(new Field("driver",r.getString(1)),new Field("directory",r.getString(2)),new Field("rejectLimit",r.getString(3)));
        },user?new Object[]{e.name()}:new Object[]{e.owner(),e.name()});
        if(result.size()!=1)throw new IllegalStateException("Catalog entry changed; refresh required");
        return result.getFirst();
    }
    private AgentViewColumns columns(String view){
        // View is composed only from fixed catalog names; zero rows, no secret values fetched.
        return jdbc.query("SELECT * FROM "+view+" WHERE 1=0",(ResultSetExtractor<AgentViewColumns>)rs->{
            var metadata=rs.getMetaData();var names=new ArrayList<String>();
            for(int i=1;i<=metadata.getColumnCount();i++)names.add(metadata.getColumnName(i));return new AgentViewColumns(names);
        });
    }
    public static String locationSql(String section,boolean own,boolean fallback){
        String suffix=switch(section){case "locations"->"EXTERNAL_LOCATIONS";case "partitions"->"XTERNAL_LOC_PARTITIONS";case "subpartitions"->"XTERNAL_LOC_SUBPARTITIONS";default->throw new IllegalArgumentException("Invalid section");};
        String projection=section.equals("locations")?"NULL, NULL":section.equals("partitions")?"PARTITION_NAME, NULL":"PARTITION_NAME, SUBPARTITION_NAME";
        String owner=section.equals("locations")?"OWNER":"TABLE_OWNER";
        return "SELECT "+projection+", DIRECTORY_NAME, LOCATION FROM SYS."+(own?"USER":fallback?"ALL":"DBA")+"_"+suffix+" WHERE "+(own?"":owner+" = ? AND ")+"TABLE_NAME = ? ORDER BY 1, 2, 3, 4";
    }
    private Section locations(Entry entry,boolean own,String section){
        Section result=readLocations(entry,own,false,section);
        return !own&&result.status().equals("ACCESS_REQUIRED")?readLocations(entry,false,true,section):result;
    }
    private Section readLocations(Entry entry,boolean own,boolean fallback,String section){
        String sql=locationSql(section,own,fallback);String source=sql.substring(sql.indexOf("SYS."),sql.indexOf(" WHERE"));
        try{return new Section(section,rows(sql,(r,n)->new Location(r.getString(1),r.getString(2),r.getString(3),ExternalAddress.location(r.getString(4))),own?new Object[]{entry.name()}:new Object[]{entry.owner(),entry.name()}),source,"AVAILABLE","");}
        catch(RuntimeException ex){return new Section(section,List.of(),source,status(ex),error(ex));}
    }
    private <T>List<T> rows(String sql,RowMapper<T> mapper,Object... args){
        List<T> result=jdbc.query(sql+" FETCH FIRST "+(LIMIT+1)+" ROWS ONLY",mapper,args);
        if(result.size()>LIMIT)throw new LimitExceeded();return result;
    }
    private static final class LimitExceeded extends RuntimeException {}
    public AclCatalog acl(){
        AclCatalog result=acl(false);
        return result.status().equals("ACCESS_REQUIRED")?acl(true):result;
    }
    private AclCatalog acl(boolean user){
        String view="SYS."+(user?"USER":"DBA")+"_HOST_ACES";
        try{
            var columns=columns(view);
            return new AclCatalog(rows(aclSql(user,columns),(r,n)->new Ace(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12))),view,user?"USER":"DATABASE","AVAILABLE","");
        }catch(RuntimeException ex){return new AclCatalog(List.of(),view,user?"USER":"DATABASE",status(ex),error(ex));}
    }
    public static String aclSql(boolean user,AgentViewColumns columns){
        String principal=user?"SYS_CONTEXT('USERENV','SESSION_USER')":columns.projection("PRINCIPAL");
        return "SELECT HOST, LOWER_PORT, UPPER_PORT, "+principal+", "+columns.projection("PRINCIPAL_TYPE")+", PRIVILEGE, "+columns.projection("GRANT_TYPE")+", "+columns.projection("STATUS")+", "+columns.projection("ACE_ORDER")+", "+columns.projection("START_DATE")+", "+columns.projection("END_DATE")+", "+columns.projection("INVERTED_PRINCIPAL")
            +" FROM SYS."+(user?"USER":"DBA")+"_HOST_ACES ORDER BY HOST, LOWER_PORT, UPPER_PORT, 9, 4, PRIVILEGE";
    }
    public SchemaAcl.Roles roles(String schema){
        try{
            var walk=new SchemaAcl.Walk(schema);
            for(List<String> batch=walk.next();!batch.isEmpty();batch=walk.next()){
                List<SchemaAcl.Grant> found=rows(roleSql(batch.size()),(r,n)->new SchemaAcl.Grant(r.getString(1),r.getString(2),r.getString(3)),batch.toArray());
                walk.accept(batch,found);
            }
            return new SchemaAcl.Roles(walk.grants(),"AVAILABLE","");
        }catch(RuntimeException ex){return new SchemaAcl.Roles(List.of(),ex instanceof SchemaAcl.LimitExceeded?"LIMIT":status(ex),error(ex));}
    }
    public static String roleSql(int count){
        if(count<1||count>200)throw new IllegalArgumentException("Invalid role batch size");
        return "SELECT GRANTEE, GRANTED_ROLE, DEFAULT_ROLE FROM SYS.DBA_ROLE_PRIVS WHERE GRANTEE IN ("
            +String.join(",",Collections.nCopies(count,"?"))+") ORDER BY GRANTEE, GRANTED_ROLE, DEFAULT_ROLE";
    }
    public static String status(Throwable ex){return ex instanceof LimitExceeded?"LIMIT":CredentialCatalogRepository.status(ex);}
    public static String error(Throwable ex){return CredentialCatalogRepository.error(ex);}
}
