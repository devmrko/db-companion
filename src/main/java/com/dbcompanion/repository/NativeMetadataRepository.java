package com.dbcompanion.repository;

import com.dbcompanion.model.NativeMetadata.*;
import com.dbcompanion.model.NativeMetadata;
import com.dbcompanion.model.Ontology;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class NativeMetadataRepository {
    public static final String ENABLE_JAVA="BEGIN DBMS_CLOUD_ADMIN.ENABLE_FEATURE(feature_name => 'JAVAVM'); END;";
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public NativeMetadataRepository(JdbcTemplate jdbc,JsonMapper json){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(90);this.json=json;}
    public static String sql(String file){
        if(!Set.of("setup","capture","read","snapshots","candidates").contains(file))throw new IllegalArgumentException("Unknown RDF script");
        try{return new ClassPathResource("sql/ontology-native/"+file+".sql").getContentAsString(StandardCharsets.UTF_8);}
        catch(IOException ex){throw new IllegalStateException("Missing RDF script",ex);}
    }
    public boolean api(){
        return jdbc.queryForObject("SELECT COUNT(DISTINCT p.PROCEDURE_NAME) FROM ALL_PROCEDURES p JOIN ALL_SYNONYMS s ON s.TABLE_OWNER=p.OWNER AND s.TABLE_NAME=p.OBJECT_NAME WHERE s.OWNER='PUBLIC' AND s.SYNONYM_NAME='SEM_APIS' AND p.PROCEDURE_NAME IN ('CREATE_RDF_NETWORK','CREATE_RDF_GRAPH')",Integer.class)==2;
    }
    public String javaState(){
        try {
            var states=jdbc.queryForList("SELECT STATUS FROM DBA_REGISTRY WHERE COMP_ID='JAVAVM'",String.class);
            if(states.isEmpty())return "MISSING";
            return switch(states.getFirst()){case "VALID" -> "READY";case "LOADING","UPGRADING" -> "LOADING";default -> "UNAVAILABLE";};
        } catch(org.springframework.dao.DataAccessException inaccessible){
            // Ordinary owners may use RDF without access to DBA_REGISTRY.
            try{return jdbc.queryForObject("SELECT DBMS_JAVA.GET_JDK_VERSION FROM DUAL",String.class)==null?"UNKNOWN":"READY";}
            catch(org.springframework.dao.DataAccessException ex){
                for(Throwable cause=ex;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql){
                    if(sql.getErrorCode()==29538)return "MISSING";
                    if(sql.getErrorCode()==29548)return "LOADING";
                }
                return "UNKNOWN";
            }
        }
    }
    public boolean javaSetupAvailable(){
        return jdbc.queryForObject("SELECT COUNT(*) FROM ALL_PROCEDURES WHERE OBJECT_NAME='DBMS_CLOUD_ADMIN' AND PROCEDURE_NAME='ENABLE_FEATURE'",Integer.class)>0;
    }
    public void enableJava(){jdbc.execute(ENABLE_JAVA);}
    public void requireJava(){if(!javaState().equals("READY"))throw new Ontology.Failure(409,"native.javaRequired");}
    public String storage(){
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM USER_OBJECTS WHERE OBJECT_NAME LIKE 'DBC!_META!_RDF#%' ESCAPE '!'",Integer.class);
        if(count==0)return "MISSING";
        var types=jdbc.queryForList("SELECT OBJECT_TYPE FROM USER_OBJECTS WHERE OBJECT_NAME='DBC_META_RDF#RDFT_DBC_METADATA' AND STATUS='VALID'",String.class);
        var columns=jdbc.queryForList("SELECT COLUMN_NAME||':'||DATA_TYPE_OWNER||':'||DATA_TYPE FROM USER_TAB_COLUMNS WHERE TABLE_NAME='DBC_META_RDF#RDFT_DBC_METADATA' ORDER BY COLUMN_ID",String.class);
        return types.equals(List.of("VIEW"))&&columns.equals(List.of("TRIPLE:MDSYS:SDO_RDF_TRIPLE_S"))?"READY":"MISMATCH";
    }
    public void require(){requireJava();if(!storage().equals("READY"))throw new Ontology.Failure(409,"native.notReady");}
    public void install(){requireJava();if(!storage().equals("MISSING"))throw new Ontology.Failure(409,"native.mismatch");jdbc.execute(sql("setup"));require();}
    public Capture capture(String table){
        require();Ontology.name(table);
        return jdbc.execute((ConnectionCallback<Capture>)c->{try(var call=c.prepareCall(sql("capture"))){
            call.setQueryTimeout(90);call.setString(1,table);call.registerOutParameter(2,Types.VARCHAR);call.registerOutParameter(3,Types.NUMERIC);call.execute();
            String iri=call.getString(2);NativeMetadata.snapshot(iri);return new Capture(table,iri,call.getInt(3));
        }});
    }
    public List<Snapshot> snapshots(){
        return jdbc.execute((ConnectionCallback<List<Snapshot>>)c->{try(var call=c.prepareCall(sql("snapshots"))){
            call.setQueryTimeout(90);call.registerOutParameter(1,Types.REF_CURSOR);call.execute();
            try(var r=(ResultSet)call.getObject(1)){var rows=new ArrayList<Snapshot>();while(r.next()){
                if(rows.size()>=500)throw new Ontology.Failure(413,"native.limit");
                String iri=r.getString(1);NativeMetadata.snapshot(iri);rows.add(new Snapshot(iri,r.getString(2),r.getString(3),r.getString(4),r.getString(5)));
            }return List.copyOf(rows);}
        }});
    }
    public Map<String,Object> diagnostics(String owner){
        require();
        String table=com.dbcompanion.common.db.ProfileHistorySql.object(owner,"DBC_META_RDF#RDFT_DBC_METADATA");
        var stored=jdbc.queryForList("SELECT t.triple.GET_MODEL(?, 'DBC_META_RDF') AS GRAPH, "
            +"t.triple.GET_SUBJECT(?, 'DBC_META_RDF') AS SUBJECT, "
            +"t.triple.GET_PROPERTY(?, 'DBC_META_RDF') AS PREDICATE, "
            +"t.triple.GET_OBJ_VALUE(?, 'DBC_META_RDF') AS OBJECT FROM "+table+" t FETCH FIRST 30 ROWS ONLY",owner,owner,owner,owner);
        String scope="'"+owner.replace("'","''")+"'";
        var matched=jdbc.queryForList("SELECT g,s,p,o FROM TABLE(SEM_MATCH('SELECT ?g ?s ?p ?o WHERE { GRAPH ?g { ?s ?p ?o } }', SEM_MODELS('DBC_METADATA'), NULL,NULL,NULL,NULL,'DO_UNESCAPE=T',NULL,NULL,"+scope+",'DBC_META_RDF')) FETCH FIRST 30 ROWS ONLY");
        String header="PREFIX m: <urn:dbc:meta:> PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> SELECT ?g ?s ?name ?at ?actor ?kind WHERE { GRAPH ?g { ?s m:object ?o ; m:capturedAt ?at ; m:capturedBy ?actor . ?o m:name ?name ; rdf:type ?kind . } }";
        var headers=jdbc.queryForList("SELECT g,s,name,at,actor,kind FROM TABLE(SEM_MATCH('"+header+"', SEM_MODELS('DBC_METADATA'), NULL,NULL,NULL,NULL,'DO_UNESCAPE=T',NULL,NULL,"+scope+",'DBC_META_RDF')) FETCH FIRST 30 ROWS ONLY");
        var procedural=jdbc.queryForList("SELECT g,s,name,at,actor,kind FROM TABLE(SEM_MATCH('"+header+"', SEM_MODELS('DBC_METADATA'), NULL,NULL,NULL,NULL,'DO_UNESCAPE=T REWRITE=F',NULL,NULL,"+scope+",'DBC_META_RDF')) FETCH FIRST 30 ROWS ONLY");
        return Map.of("storedTriples",stored,"matchedTriples",matched,"captureHeaders",headers,"proceduralHeaders",procedural,"snapshots",snapshots());
    }
    public List<Match> candidates(List<Snapshot> snapshots){
        String selection=json.writeValueAsString(snapshots.stream().map(Snapshot::iri).toList());
        return jdbc.execute((ConnectionCallback<List<Match>>)c->{try(var call=c.prepareCall(sql("candidates"))){
            call.setQueryTimeout(90);call.setString(1,selection);call.registerOutParameter(2,Types.REF_CURSOR);call.execute();
            try(var r=(ResultSet)call.getObject(2)){var rows=new ArrayList<Match>();while(r.next()){
                if(rows.size()>=NativeMetadata.MAX_CANDIDATES)throw new Ontology.Failure(413,"native.limit");
                rows.add(new Match(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)));
            }return List.copyOf(rows);}
        }});
    }
    public List<Map<String,String>> columns(String snapshot){
        NativeMetadata.snapshot(snapshot);
        return jdbc.execute((ConnectionCallback<List<Map<String,String>>>)c->{try(var call=c.prepareCall(sql("read"))){
            call.setQueryTimeout(90);call.setString(1,snapshot);call.registerOutParameter(2,Types.REF_CURSOR);call.execute();
            try(var r=(ResultSet)call.getObject(2)){var rows=new ArrayList<Map<String,String>>();while(r.next()){
                if(rows.size()>=1000)throw new Ontology.Failure(413,"native.limit");var row=new LinkedHashMap<String,String>();
                for(String key:List.of("COLUMN_NAME","DATA_TYPE","NULLABLE","COMMENTS","COLUMN_ID"))row.put(key,Objects.toString(r.getString(key),""));rows.add(row);
            }return List.copyOf(rows);}
        }});
    }
}
