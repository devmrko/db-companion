package com.dbcompanion.repository;

import com.dbcompanion.common.db.AppRecordSql;
import com.dbcompanion.model.*;
import com.dbcompanion.model.OntologyDiscoveryArchive.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.model.OntologyDiscoveryArchive.CALL;
import static com.dbcompanion.model.OntologyDiscoveryArchive.PLAN;

/** Uses the already explicit app-record installation; no automatic DDL or grants. */
@Repository
public class OntologyDiscoveryRepository {
    private final JdbcTemplate jdbc;private final AppRecordRepository records;private final JsonMapper json;
    public OntologyDiscoveryRepository(JdbcTemplate jdbc,AppRecordRepository records,JsonMapper json){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(15);this.records=records;this.json=json;}
    public void require(String schema,String login){QueryArchive.owner(schema,login);records.require(schema,login);}
    public boolean exists(String schema,String id){return records.detail(schema,QueryArchive.id(id),PLAN)!=null;}
    public void saveRun(Run run){
        var existing=records.detail(run.schema(),run.id(),PLAN);
        if(existing!=null){if(!existing.item().state().equals("SUCCEEDED")||!json.treeToValue(existing.payload(),Run.class).equals(run))throw new Ontology.Failure(409,"stale");return;}
        var payload=json.valueToTree(run);records.begin(run.schema(),run.id(),PLAN,payload);
        if(!records.finish(run.schema(),run.id(),"SUCCEEDED",payload))throw new Ontology.Failure(409,"stale");
    }
    public Run run(String schema,String database,String id){
        var found=records.detail(schema,QueryArchive.id(id),PLAN);if(found==null||!found.item().state().equals("SUCCEEDED"))throw new Ontology.Failure(404,"notFound");
        var run=json.treeToValue(found.payload(),Run.class);if(run.format()!=1||!schema.equals(run.schema())||!database.equals(run.database())||!id.equals(run.id()))throw new Ontology.Failure(409,"mismatch");return run;
    }
    public void claim(String schema,Receipt receipt){records.begin(schema,OntologyDiscoveryArchive.callId(receipt.runId(),receipt.index()),CALL,json.valueToTree(receipt));}
    public void finish(String schema,Receipt receipt,String state){if(!records.finish(schema,OntologyDiscoveryArchive.callId(receipt.runId(),receipt.index()),state,json.valueToTree(receipt)))throw new Ontology.Failure(409,"stale");}
    public Call call(String schema,String token,int index){
        var id=OntologyDiscoveryArchive.callId(token,index);var detail=records.detail(schema,id,CALL);if(detail==null)throw new Ontology.Failure(404,"notFound");return new Call(id,detail.item().state(),json.treeToValue(detail.payload(),Receipt.class));
    }
    /** Recover only a received, validated output whose candidate-save transaction failed. No new AI request. */
    public void recovered(String schema,Receipt receipt){
        String value=json.writeValueAsString(receipt);if(value.length()>QueryArchive.MAX_JSON)throw new Ontology.Failure(413,"archive.limit");
        if(jdbc.update("UPDATE "+AppRecordSql.table(schema)+" SET STATE='SUCCEEDED',PAYLOAD=?,UPDATED_AT=SYSTIMESTAMP WHERE RECORD_ID=? AND RECORD_TYPE=? AND STATE='CHECK_REQUIRED' AND JSON_VALUE(PAYLOAD,'$.stage')='SAVE'",s->{s.setCharacterStream(1,new java.io.StringReader(value),value.length());s.setString(2,OntologyDiscoveryArchive.callId(receipt.runId(),receipt.index()));s.setString(3,CALL);})!=1)throw new Ontology.Failure(409,"stale");
    }
    public List<Call> calls(String schema,String id){
        QueryArchive.id(id);return jdbc.query("SELECT RECORD_ID,STATE,PAYLOAD FROM "+AppRecordSql.table(schema)+" WHERE RECORD_TYPE=? AND JSON_VALUE(PAYLOAD,'$.runId')=? ORDER BY SEQ",(r,n)->{
            var clob=r.getClob(3);try{if(clob==null||clob.length()>QueryArchive.MAX_JSON)throw new Ontology.Failure(409,"mismatch");var value=json.readValue(clob.getSubString(1,(int)clob.length()),Receipt.class);if(!value.runId().equals(id)||!r.getString(1).equals(OntologyDiscoveryArchive.callId(id,value.index())))throw new Ontology.Failure(409,"mismatch");return new Call(r.getString(1),r.getString(2),new Receipt(value.runId(),value.index(),value.stage(),"",value.relations(),value.issues()));}finally{if(clob!=null)clob.free();}
        },CALL,id);
    }
    public Page page(String schema,String database,String before){
        Ontology.cursor(before);var args=new ArrayList<Object>(List.of(PLAN,database));if(!before.isEmpty())args.add(new java.math.BigDecimal(before));var sequences=new ArrayList<String>();
        var rows=jdbc.query("SELECT SEQ,RECORD_ID,RECORDED_AT,JSON_VALUE(PAYLOAD,'$.profile.selection.name'),JSON_VALUE(PAYLOAD,'$.calls'),JSON_VALUE(PAYLOAD,'$.tables') FROM "+AppRecordSql.table(schema)+" WHERE RECORD_TYPE=? AND STATE='SUCCEEDED' AND JSON_VALUE(PAYLOAD,'$.database')=?"+(before.isEmpty()?"":" AND SEQ<?")+" ORDER BY SEQ DESC FETCH FIRST 21 ROWS ONLY",(r,n)->{sequences.add(r.getString(1));return new Saved(r.getString(2),r.getString(3),r.getString(4),r.getInt(6),r.getInt(5));},args.toArray());
        return new Page(rows.stream().limit(20).toList(),rows.size()>20?sequences.get(19):"");
    }
}
