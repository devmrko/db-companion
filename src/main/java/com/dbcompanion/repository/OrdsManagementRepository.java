package com.dbcompanion.repository;

import com.dbcompanion.model.OrdsManagement;
import com.dbcompanion.model.OrdsManagement.*;
import java.io.StringReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrdsManagementRepository {
    private static final List<String> TABLES=List.of("SCHEMAS","MODULES","TEMPLATES","HANDLERS","PARAMETERS");
    private final JdbcTemplate jdbc;
    public OrdsManagementRepository(JdbcTemplate jdbc) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(15);
    }
    public Snapshot snapshot() {
        try {
            Set<String> apis=new TreeSet<>(jdbc.queryForList("SELECT PROCEDURE_NAME FROM ALL_PROCEDURES WHERE OWNER = 'ORDS_METADATA' AND OBJECT_NAME = 'ORDS' AND PROCEDURE_NAME IS NOT NULL",String.class));
            if(apis.isEmpty())return unavailable("NOT_DETECTED");
            Map<String,List<Map<String,String>>> data=new TreeMap<>();
            int[] budget={0};
            for(String table:TABLES)data.put(table,read(table,budget));
            return new Snapshot("AVAILABLE","",Collections.unmodifiableSet(apis),Collections.unmodifiableMap(data),OrdsManagement.digest(data));
        } catch(Failure ex) {return unavailable(ex.getMessage());}
        catch(RuntimeException ex) {return unavailable(errorKey(ex));}
    }
    private Snapshot unavailable(String status) {return new Snapshot(status,"",Set.of(),Map.of(),"");}
    private List<Map<String,String>> read(String table,int[] budget) {
        // Only the fixed USER_ORDS metadata allowlist is queried. No OAuth secret-bearing views.
        List<Map<String,String>> rows=jdbc.query("SELECT * FROM USER_ORDS_"+table+" ORDER BY ID FETCH FIRST 2001 ROWS ONLY",(rs,n)->{
            if(n>=2000)throw new Failure(413,"LIMIT");
            var row=new TreeMap<String,String>();var meta=rs.getMetaData();
            for(int i=1;i<=meta.getColumnCount();i++){
                String value;
                if(meta.getColumnType(i)==java.sql.Types.CLOB||meta.getColumnType(i)==java.sql.Types.NCLOB){
                    try(var reader=rs.getCharacterStream(i)){
                        if(reader==null)value=null;
                        else{var text=new StringBuilder();char[] chunk=new char[4096];int count;
                            while((count=reader.read(chunk))!=-1){text.append(chunk,0,count);if(text.length()>2_000_000-budget[0])throw new Failure(413,"LIMIT");}value=text.toString();}
                    }catch(IOException ex){throw new SQLException("ORDS metadata read failed",ex);}
                }else value=rs.getString(i);
                if(value!=null){budget[0]+=value.getBytes(StandardCharsets.UTF_8).length;if(budget[0]>2_000_000)throw new Failure(413,"LIMIT");}
                row.put(meta.getColumnLabel(i).toUpperCase(Locale.ROOT),value);
            }
            return Collections.unmodifiableMap(row);
        });
        return List.copyOf(rows);
    }
    public void execute(List<Statement> statements) {
        jdbc.execute((ConnectionCallback<Void>)connection->{
            for(Statement statement:statements)try(var call=connection.prepareCall(statement.sql())) {
                call.setQueryTimeout(20);
                for(int i=0;i<statement.bindings().size();i++) {
                    String value=statement.bindings().get(i);
                    // CLOB source is metadata text; it is never executed or explained by the app.
                    if(value!=null&&value.length()>4000)call.setCharacterStream(i+1,new StringReader(value),value.length());
                    else call.setString(i+1,value);
                }
                call.execute();
            }
            return null;
        });
    }
    public static String errorKey(Throwable ex) {
        for(Throwable cause=ex;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql){
            if(sql.getErrorCode()==1031)return "ACCESS_REQUIRED";
            if(sql.getErrorCode()==942)return "METADATA_UNAVAILABLE";
            if(sql.getErrorCode()==904||sql.getErrorCode()==6550)return "UNSUPPORTED";
            if(sql.getErrorCode()==8177)return "stale";
        }
        return "failed";
    }
}
