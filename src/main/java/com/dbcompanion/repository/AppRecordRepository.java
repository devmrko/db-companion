package com.dbcompanion.repository;

import com.dbcompanion.common.db.AppRecordSql;
import com.dbcompanion.model.*;
import com.dbcompanion.model.QueryArchive.*;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AppRecordRepository {
    private final JdbcTemplate jdbc;private final JsonMapper json;
    public AppRecordRepository(JdbcTemplate jdbc,JsonMapper json){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);this.json=json;}
    public static List<String> layout(){return List.of("SEQ:NUMBER:N:0","RECORD_ID:VARCHAR2:N:36","RECORD_TYPE:VARCHAR2:N:32","FORMAT_VERSION:NUMBER:N:0","STATE:VARCHAR2:N:24","ACTOR:VARCHAR2:N:128","RECORDED_AT:TIMESTAMP(6) WITH TIME ZONE:N:0","UPDATED_AT:TIMESTAMP(6) WITH TIME ZONE:N:0","PAYLOAD:CLOB:N:0");}
    public String status(String schema,String login){
        var types=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=?",String.class,schema,AppRecordSql.TABLE);
        if(types.isEmpty())return schema.equals(login)?"MISSING":"UNAVAILABLE";
        if(!types.equals(List.of("TABLE")))return "MISMATCH";
        var columns=jdbc.query("SELECT COLUMN_NAME,DATA_TYPE,NULLABLE,CHAR_LENGTH FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",(r,n)->r.getString(1)+":"+r.getString(2)+":"+r.getString(3)+":"+r.getInt(4),schema,AppRecordSql.TABLE);
        if(!columns.equals(layout()))return "MISMATCH";
        var keys=jdbc.query("SELECT c.CONSTRAINT_TYPE,LISTAGG(k.COLUMN_NAME,',') WITHIN GROUP (ORDER BY k.POSITION) FROM SYS.ALL_CONSTRAINTS c JOIN SYS.ALL_CONS_COLUMNS k ON k.OWNER=c.OWNER AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME AND k.TABLE_NAME=c.TABLE_NAME WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE IN ('P','U') AND c.STATUS='ENABLED' AND c.VALIDATED='VALIDATED' GROUP BY c.CONSTRAINT_TYPE,c.CONSTRAINT_NAME",(r,n)->r.getString(1)+":"+r.getString(2),schema,AppRecordSql.TABLE);
        if(!new HashSet<>(keys).equals(Set.of("P:SEQ","U:RECORD_ID")))return "MISMATCH";
        for(var check:Map.of("DBC_APP_RECORD_V1","FORMAT_VERSION=1","DBC_APP_RECORD_JSON","PAYLOADISJSON").entrySet()){
            var values=jdbc.queryForList("SELECT SEARCH_CONDITION_VC FROM SYS.ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? AND CONSTRAINT_NAME=? AND CONSTRAINT_TYPE='C' AND STATUS='ENABLED' AND VALIDATED='VALIDATED'",String.class,schema,AppRecordSql.TABLE,check.getKey());
            if(values.size()!=1||!Objects.toString(values.getFirst(),"").replaceAll("[\\s\"()]","").equalsIgnoreCase(check.getValue()))return "MISMATCH";
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TAB_IDENTITY_COLS WHERE OWNER=? AND TABLE_NAME=? AND COLUMN_NAME='SEQ' AND GENERATION_TYPE='ALWAYS'",Long.class,schema,AppRecordSql.TABLE)!=1L)return "MISMATCH";
        if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME=? AND STATUS='ENABLED'",Long.class,schema,AppRecordSql.TABLE)!=0L)return "MISMATCH";
        return "READY";
    }
    public void require(String schema,String login){if(!status(schema,login).equals("READY"))throw new Ontology.Failure(409,"archive.notReady");}
    public void install(String schema,String login){QueryArchive.owner(schema,login);var state=status(schema,login);if(state.equals("READY"))return;if(!state.equals("MISSING"))throw new Ontology.Failure(409,"mismatch");jdbc.execute(AppRecordSql.create(schema));require(schema,login);}
    private String encode(JsonNode payload){String value=json.writeValueAsString(payload);if(!payload.isObject()||value.length()>QueryArchive.MAX_JSON)throw new Ontology.Failure(413,"archive.limit");return value;}
    public void begin(String schema,String id,String type,JsonNode payload){
        QueryArchive.id(id);if(!Set.of(QueryArchive.TYPE,QueryArchive.STORE,OntologyScope.TYPE).contains(type))throw new Ontology.Failure(400,"archive.invalid");var value=encode(payload);
        jdbc.update("INSERT INTO "+AppRecordSql.table(schema)+" (RECORD_ID,RECORD_TYPE,STATE,PAYLOAD) VALUES (?,?,'REQUESTED',?)",s->{s.setString(1,id);s.setString(2,type);s.setCharacterStream(3,new StringReader(value),value.length());});
    }
    /** Only transition a pending record; a late failure must never overwrite a committed success. */
    public boolean finish(String schema,String id,String state,JsonNode payload){
        QueryArchive.id(id);if(!Set.of("SUCCEEDED","FAILED","CHECK_REQUIRED").contains(state))throw new Ontology.Failure(400,"archive.invalid");var value=encode(payload);
        return jdbc.update("UPDATE "+AppRecordSql.table(schema)+" SET STATE=?,PAYLOAD=?,UPDATED_AT=SYSTIMESTAMP WHERE RECORD_ID=? AND STATE='REQUESTED'",s->{s.setString(1,state);s.setCharacterStream(2,new StringReader(value),value.length());s.setString(3,id);})==1;
    }
    public Detail detail(String schema,String id,String type){
        QueryArchive.id(id);var rows=jdbc.query("SELECT SEQ,RECORD_ID,STATE,ACTOR,RECORDED_AT,UPDATED_AT,PAYLOAD FROM "+AppRecordSql.table(schema)+" WHERE RECORD_ID=? AND RECORD_TYPE=?",(r,n)->{
            var clob=r.getClob(7);try{if(clob==null||clob.length()>QueryArchive.MAX_JSON)throw new Ontology.Failure(409,"mismatch");var payload=json.readTree(clob.getSubString(1,(int)clob.length()));if(!payload.isObject())throw new Ontology.Failure(409,"mismatch");return new Detail(new Item(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),payload.path("question").asString("")),payload);}finally{if(clob!=null)clob.free();}
        },id,type);return rows.isEmpty()?null:rows.getFirst();
    }
    public Page page(String schema,String before){
        Ontology.cursor(before);var args=new ArrayList<Object>(List.of(QueryArchive.TYPE));if(!before.isEmpty())args.add(new BigDecimal(before));
        var rows=jdbc.query("SELECT SEQ,RECORD_ID,STATE,ACTOR,RECORDED_AT,UPDATED_AT,PAYLOAD FROM "+AppRecordSql.table(schema)+" WHERE RECORD_TYPE=?"+(before.isEmpty()?"":" AND SEQ<?")+" ORDER BY SEQ DESC FETCH FIRST 11 ROWS ONLY",(r,n)->{
            var clob=r.getClob(7);try{if(clob==null||clob.length()>QueryArchive.MAX_JSON)throw new Ontology.Failure(409,"mismatch");var p=json.readTree(clob.getSubString(1,(int)clob.length()));return new Item(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),p.path("question").asString(""));}finally{if(clob!=null)clob.free();}
        },args.toArray());
        var page=rows.stream().limit(10).toList();return new Page(page,rows.size()>10?page.getLast().seq():"");
    }
    /** Scope snapshots are separate from query archives. No RDF store is required. */
    public OntologyScope.Page scopes(String schema,String database,String before){
        Ontology.cursor(before);var args=new ArrayList<Object>(List.of(OntologyScope.TYPE));if(!before.isEmpty())args.add(new BigDecimal(before));
        var sequences=new ArrayList<String>();
        var rows=jdbc.query("SELECT SEQ,RECORD_ID,RECORDED_AT,PAYLOAD FROM "+AppRecordSql.table(schema)+" WHERE RECORD_TYPE=? AND STATE='SUCCEEDED'"+(before.isEmpty()?"":" AND SEQ<?")+" ORDER BY SEQ DESC FETCH FIRST 21 ROWS ONLY",(r,n)->{
            sequences.add(r.getString(1));var clob=r.getClob(4);
            try{
                if(clob==null||clob.length()>100_000)throw new Ontology.Failure(409,"mismatch");
                var selection=json.readValue(clob.getSubString(1,(int)clob.length()),OntologyScope.Selection.class);
                if(!schema.equals(selection.schema())||!database.equals(selection.database()))throw new Ontology.Failure(409,"mismatch");
                return new OntologyScope.Saved(r.getString(2),selection.name(),selection.tables(),r.getString(3));
            }finally{if(clob!=null)clob.free();}
        },args.toArray());
        return new OntologyScope.Page(rows.stream().limit(20).toList(),rows.size()>20?sequences.get(19):"");
    }
}
