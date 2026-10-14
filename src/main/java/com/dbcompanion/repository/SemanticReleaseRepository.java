package com.dbcompanion.repository;

import com.dbcompanion.common.db.AppRecordSql;
import com.dbcompanion.model.*;
import com.dbcompanion.model.SemanticRelease.*;
import java.io.StringReader;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SemanticReleaseRepository {
    private final JdbcTemplate jdbc;private final JsonMapper json;
    public SemanticReleaseRepository(JdbcTemplate jdbc,JsonMapper json){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(30);this.json=json;}
    private String payload(java.sql.Clob value)throws java.sql.SQLException{
        if(value==null)throw SemanticRelease.invalid();try{if(value.length()>QueryArchive.MAX_JSON)throw SemanticRelease.invalid();return value.getSubString(1,(int)value.length());}finally{value.free();}
    }
    public record Row(String id,String actor,String recordedAt,String payload){}
    public List<Row> list(String owner,String type){
        return jdbc.query("SELECT RECORD_ID,ACTOR,RECORDED_AT,PAYLOAD FROM "+AppRecordSql.table(owner)+" WHERE RECORD_TYPE=? AND STATE='SUCCEEDED' ORDER BY SEQ DESC FETCH FIRST 101 ROWS ONLY",
            (r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),payload(r.getClob(4))),type);
    }
    public Row get(String owner,String id){
        QueryArchive.id(id);var values=jdbc.query("SELECT RECORD_ID,ACTOR,RECORDED_AT,PAYLOAD FROM "+AppRecordSql.table(owner)+" WHERE RECORD_ID=? AND RECORD_TYPE=? AND STATE='SUCCEEDED'",
            (r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),payload(r.getClob(4))),id,SemanticRelease.TYPE);
        if(values.size()!=1)throw SemanticRelease.invalid();return values.getFirst();
    }
    public void insert(String owner,String id,String type,Object value){
        if(!Set.of(SemanticRelease.TYPE,SemanticRelease.DEPLOY,SemanticRelease.POINTER,SemanticRelease.PREFERENCE).contains(type))throw SemanticRelease.invalid();
        String text=json.writeValueAsString(value);if(text.length()>QueryArchive.MAX_JSON)throw SemanticRelease.invalid();
        jdbc.update("INSERT INTO "+AppRecordSql.table(owner)+" (RECORD_ID,RECORD_TYPE,STATE,PAYLOAD) VALUES (?,?,'SUCCEEDED',?)",s->{s.setString(1,id);s.setString(2,type);s.setCharacterStream(3,new StringReader(text),text.length());});
    }
    public Head head(String owner){
        var values=jdbc.query("SELECT PAYLOAD FROM "+AppRecordSql.table(owner)+" WHERE RECORD_ID=? AND RECORD_TYPE=? AND STATE='SUCCEEDED'",(r,n)->json.readValue(payload(r.getClob(1)),Head.class),SemanticRelease.POINTER_ID,SemanticRelease.POINTER);
        return values.isEmpty()?new Head(0,""):values.getFirst();
    }
    public Preference preference(String owner){
        var values=jdbc.query("SELECT PAYLOAD FROM "+AppRecordSql.table(owner)+" WHERE RECORD_ID=? AND RECORD_TYPE=? AND STATE='SUCCEEDED'",(r,n)->json.readValue(payload(r.getClob(1)),Preference.class),SemanticRelease.PREFERENCE_ID,SemanticRelease.PREFERENCE);
        return values.isEmpty()?new Preference(0,"DEFAULT"):values.getFirst();
    }
    public void preference(String owner,Preference expected,String selection){
        var next=new Preference(expected.revision()+1,selection);
        if(expected.revision()==0){try{insert(owner,SemanticRelease.PREFERENCE_ID,SemanticRelease.PREFERENCE,next);}catch(org.springframework.dao.DuplicateKeyException ex){throw SemanticRelease.invalid();}}
        else {
            String text=json.writeValueAsString(next);
            if(jdbc.update("UPDATE "+AppRecordSql.table(owner)+" SET PAYLOAD=?,ACTOR=SYS_CONTEXT('USERENV','SESSION_USER'),UPDATED_AT=SYSTIMESTAMP WHERE RECORD_ID=? AND RECORD_TYPE=? AND JSON_VALUE(PAYLOAD,'$.revision' RETURNING NUMBER)=?",text,SemanticRelease.PREFERENCE_ID,SemanticRelease.PREFERENCE,expected.revision())!=1)throw SemanticRelease.invalid();
        }
    }
    /** Caller transaction includes the event insert; failed CAS rolls it all back. */
    public Head deploy(String owner,Head expected,String target,String reason){
        QueryArchive.id(target);Head next=new Head(expected.revision()+1,target);
        if(expected.revision()==0){
            if(!expected.releaseId().isEmpty())throw SemanticRelease.invalid();
            try{insert(owner,SemanticRelease.POINTER_ID,SemanticRelease.POINTER,next);}catch(org.springframework.dao.DuplicateKeyException ex){throw SemanticRelease.invalid();}
        }else{
            String text=json.writeValueAsString(next);
            int changed=jdbc.update("UPDATE "+AppRecordSql.table(owner)+" SET PAYLOAD=?,ACTOR=SYS_CONTEXT('USERENV','SESSION_USER'),UPDATED_AT=SYSTIMESTAMP WHERE RECORD_ID=? AND RECORD_TYPE=? AND STATE='SUCCEEDED' AND JSON_VALUE(PAYLOAD,'$.revision' RETURNING NUMBER)=? AND JSON_VALUE(PAYLOAD,'$.releaseId')=?",s->{s.setCharacterStream(1,new StringReader(text),text.length());s.setString(2,SemanticRelease.POINTER_ID);s.setString(3,SemanticRelease.POINTER);s.setLong(4,expected.revision());s.setString(5,expected.releaseId());});
            if(changed!=1)throw SemanticRelease.invalid();
        }
        insert(owner,UUID.randomUUID().toString(),SemanticRelease.DEPLOY,new Deployment(expected.releaseId(),target,next.revision(),reason));return next;
    }
}
