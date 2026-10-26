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
    public void requireStoragePrivilege(){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE IN ('CREATE TABLE','CREATE ANY TABLE')",Integer.class)==0)
            throw BusinessGlossary.failure(403,"저장소 생성에는 CREATE TABLE 권한과 기본 테이블스페이스 할당량이 필요합니다. 관리자에게 확인해 주세요.");
    }
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
        if(!Set.of(SemanticRelease.TYPE,SemanticRelease.DEPLOY,SemanticRelease.POINTER,SemanticRelease.PREFERENCE,SemanticRelease.NUMBERS).contains(type))throw SemanticRelease.invalid();
        String text=json.writeValueAsString(value);if(text.length()>QueryArchive.MAX_JSON)throw SemanticRelease.invalid();
        jdbc.update("INSERT INTO "+AppRecordSql.table(owner)+" (RECORD_ID,RECORD_TYPE,STATE,PAYLOAD) VALUES (?,?,'SUCCEEDED',?)",s->{s.setString(1,id);s.setString(2,type);s.setCharacterStream(3,new StringReader(text),text.length());});
    }
    public Head head(String owner){
        var values=jdbc.query("SELECT PAYLOAD FROM "+AppRecordSql.table(owner)+" WHERE RECORD_ID=? AND RECORD_TYPE=? AND STATE='SUCCEEDED'",(r,n)->json.readValue(payload(r.getClob(1)),Head.class),SemanticRelease.POINTER_ID,SemanticRelease.POINTER);
        return values.isEmpty()?new Head(0,""):values.getFirst();
    }
    private List<Numbering> numbering(String owner,boolean lock){
        return jdbc.query("SELECT PAYLOAD FROM "+AppRecordSql.table(owner)+" WHERE RECORD_ID=? AND RECORD_TYPE=? AND STATE='SUCCEEDED'"+(lock?" FOR UPDATE WAIT 5":""),
            (r,n)->json.readValue(payload(r.getClob(1)),Numbering.class),SemanticRelease.NUMBERS_ID,SemanticRelease.NUMBERS);
    }
    /** Legacy versions are ordered once; the first subsequent save persists these numbers. */
    public Map<String,Long> numbers(String owner){
        var stored=numbering(owner,false);if(!stored.isEmpty())return stored.getFirst().values();
        var ids=jdbc.queryForList("SELECT RECORD_ID FROM "+AppRecordSql.table(owner)+" WHERE RECORD_TYPE=? AND STATE='SUCCEEDED' ORDER BY SEQ FETCH FIRST 10001 ROWS ONLY",String.class,SemanticRelease.TYPE);
        if(ids.size()>10000)throw BusinessGlossary.failure(413,"배포 버전 번호 관리 한도 10,000개를 초과했습니다.");
        var result=new LinkedHashMap<String,Long>();for(var id:ids)result.put(id,(long)result.size()+1);return result;
    }
    /** Same transaction as snapshot insertion. Serializes number allocation across app sessions. */
    public void assignNumber(String owner,String id){
        if(numbering(owner,false).isEmpty()){
            try{insert(owner,SemanticRelease.NUMBERS_ID,SemanticRelease.NUMBERS,new Numbering(numbers(owner)));}
            catch(org.springframework.dao.DuplicateKeyException ex){throw SemanticRelease.invalid();}
        }
        var locked=numbering(owner,true);if(locked.size()!=1)throw SemanticRelease.invalid();
        var values=new LinkedHashMap<>(locked.getFirst().values());if(values.containsKey(id))return;
        if(values.size()>=10000)throw BusinessGlossary.failure(413,"배포 버전 번호 관리 한도 10,000개를 초과했습니다.");
        values.put(QueryArchive.id(id),values.values().stream().mapToLong(Long::longValue).max().orElse(0)+1);
        String value=json.writeValueAsString(new Numbering(values));
        if(jdbc.update("UPDATE "+AppRecordSql.table(owner)+" SET PAYLOAD=?,UPDATED_AT=SYSTIMESTAMP WHERE RECORD_ID=? AND RECORD_TYPE=?",s->{s.setCharacterStream(1,new StringReader(value),value.length());s.setString(2,SemanticRelease.NUMBERS_ID);s.setString(3,SemanticRelease.NUMBERS);})!=1)throw SemanticRelease.invalid();
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
