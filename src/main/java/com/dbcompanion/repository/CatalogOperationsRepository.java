package com.dbcompanion.repository;

import com.dbcompanion.model.CatalogOperations.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogOperationsRepository {
    public static final class LimitExceeded extends RuntimeException {}
    public static final class UnsupportedShape extends RuntimeException {
        public UnsupportedShape(Level level,Collection<String> fields){super("GET_"+level.name().toUpperCase(Locale.ROOT)+" · "+String.join(", ",fields));}
    }
    public static final int LIMIT=5000;
    private final JdbcTemplate jdbc;
    private final MountedCatalogsRepository mounted;
    public CatalogOperationsRepository(JdbcTemplate source,MountedCatalogsRepository mounted){
        jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(30);this.mounted=mounted;
    }
    public MountedCatalogsRepository.Target api(){return mounted.catalogApiTarget();}
    public void requireMountApi(MountedCatalogsRepository.Target target){
        var methods=jdbc.queryForList("SELECT PROCEDURE_NAME FROM SYS.ALL_PROCEDURES WHERE OWNER = ? AND OBJECT_NAME = ? AND PROCEDURE_NAME = 'MOUNT_DB_LINK'",String.class,target.owner(),target.name());
        if(methods.isEmpty())throw new UnsupportedOperationException("MOUNT_DB_LINK is not visible");
    }
    public Link link(String user,String owner,String name){
        var rows=jdbc.query("SELECT OWNER, DB_LINK, USERNAME, HOST, CREATED FROM (SELECT SYS_CONTEXT('USERENV','SESSION_USER') OWNER, DB_LINK, USERNAME, HOST, CREATED FROM SYS.USER_DB_LINKS UNION ALL SELECT OWNER, DB_LINK, USERNAME, HOST, CREATED FROM SYS.ALL_DB_LINKS WHERE OWNER = 'PUBLIC') WHERE DB_LINK = ?",
            (r,n)->new Link(r.getString(1),r.getString(2),fingerprint(Arrays.asList(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)))),name);
        return com.dbcompanion.model.CatalogOperations.chooseLink(rows,user,owner,name);
    }
    private static String fingerprint(List<String> fields){
        try{
            var digest=MessageDigest.getInstance("SHA-256");
            for(String field:fields){byte[] bytes=String.valueOf(field).getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public static String mountSql(MountedCatalogsRepository.Target api){return "BEGIN "+api.sql()+".MOUNT_DB_LINK(catalog_name => ?, db_link => ?, enabled => TRUE); END;";}
    public static String previewSql(MountedCatalogsRepository.Target api,String catalog,String link){
        return "BEGIN\n  "+api.sql()+".MOUNT_DB_LINK(\n    catalog_name => '"+literal(catalog)+"',\n    db_link => '"+literal(link)+"',\n    enabled => TRUE\n  );\nEND;\n/";
    }
    private static String literal(String value){return value.replace("'","''");}
    public void mount(MountedCatalogsRepository.Target api,String catalog,String link){jdbc.update(mountSql(api),catalog,link);}
    public static String functionSql(MountedCatalogsRepository.Target api,Level level){
        String method=switch(level){case schemas->"GET_SCHEMAS";case tables->"GET_TABLES";case columns->"GET_COLUMNS";};
        return "TABLE("+api.sql()+"."+method+"(catalog_name => ?"+(level==Level.schemas?"":", schema_name => ?")
            +(level==Level.columns?", table_name => ?":"")+", result_limit => "+(LIMIT+1)+"))";
    }
    public static List<String> fields(Level level,Collection<String> columns){
        var candidates=switch(level){
            case schemas->List.of("SCHEMA_NAME","SCHEMA_DESCRIPTION","DESCRIPTION","COMMENTS");
            case tables->List.of("SCHEMA_NAME","TABLE_NAME","TABLE_TYPE","OBJECT_TYPE","TABLE_DESCRIPTION","DESCRIPTION","COMMENTS");
            case columns->List.of("SCHEMA_NAME","TABLE_NAME","COLUMN_NAME","COLUMN_ID","COLUMN_POSITION","DATA_TYPE","DATA_TYPE_NAME","TYPE_NAME","DATA_LENGTH","DATA_PRECISION","DATA_SCALE","NULLABLE","DESCRIPTION","COMMENTS");
        };
        String required=switch(level){case schemas->"SCHEMA_NAME";case tables->"TABLE_NAME";case columns->"COLUMN_NAME";};
        if(!columns.contains(required))throw new UnsupportedShape(level,columns);
        return candidates.stream().filter(columns::contains).toList();
    }
    public static String gridSql(MountedCatalogsRepository.Target api,Level level,List<String> fields){
        // Validate the projection again, even though callers obtain it from JDBC metadata.
        if(!fields(level,fields).equals(fields))throw new IllegalArgumentException("Unsafe projection");
        // Scope is already supplied to the API. Gateway result identifiers may be normalized
        // differently; an additional string equality can silently discard valid metadata.
        String order=switch(level){case schemas->"SCHEMA_NAME";case tables->"TABLE_NAME";case columns->fields.contains("COLUMN_ID")?"COLUMN_ID":fields.contains("COLUMN_POSITION")?"COLUMN_POSITION":"COLUMN_NAME";};
        String tieBreak=level==Level.columns&&!order.equals("COLUMN_NAME")?", \"COLUMN_NAME\"":"";
        return "SELECT "+String.join(", ",fields.stream().map(f->"\""+f+"\"").toList())+" FROM "+functionSql(api,level)+" ORDER BY \""+order+"\""+tieBreak+" FETCH FIRST "+(LIMIT+1)+" ROWS ONLY";
    }
    public static List<String> browseArguments(String catalog,Level level,String schema,String table){
        return switch(level){
            case schemas->List.of(catalog);
            case tables->List.of(catalog,schema);
            case columns->List.of(catalog,com.dbcompanion.model.CatalogOperations.apiIdentifier(schema),com.dbcompanion.model.CatalogOperations.apiIdentifier(table));
        };
    }
    public Grid browse(String catalog,Level level,String schema,String table){
        var api=api();var args=browseArguments(catalog,level,schema,table);
        var columns=jdbc.query("SELECT * FROM "+functionSql(api,level)+" WHERE 1=0",(ResultSetExtractor<List<String>>)r->{
            var result=new ArrayList<String>();var m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++)result.add(m.getColumnName(i));return result;
        },args.toArray());
        var fields=fields(level,columns);
        var rows=jdbc.query(gridSql(api,level,fields),(ResultSetExtractor<List<Map<String,String>>>)r->{
            var result=new ArrayList<Map<String,String>>();int size=0;
            while(r.next()){
                if(result.size()==LIMIT)throw new LimitExceeded();
                var row=new LinkedHashMap<String,String>();
                for(int i=0;i<fields.size();i++){
                    // Public scalar metadata only. Bound text reads to avoid unbounded CLOBs in sessions.
                    String value;
                    if(r.getMetaData().getColumnType(i+1)!=java.sql.Types.CLOB&&r.getMetaData().getColumnType(i+1)!=java.sql.Types.NCLOB){
                        value=r.getString(i+1);if(value!=null&&value.length()>8192)throw new LimitExceeded();
                    }else try(var reader=r.getCharacterStream(i+1)){
                        if(reader==null)value=null;else{char[] buffer=new char[8193];int count=0,n;
                            while(count<buffer.length&&(n=reader.read(buffer,count,buffer.length-count))!=-1)count+=n;
                            if(count>8192)throw new LimitExceeded();value=new String(buffer,0,count);}
                    }catch(java.io.IOException ex){throw new IllegalStateException("Metadata read failed",ex);}
                    size+=value==null?0:value.length();if(size>1_000_000)throw new LimitExceeded();
                    row.put(fields.get(i),value);
                }
                result.add(row);
            }
            return result;
        },args.toArray());
        return new Grid(fields,rows);
    }
}
