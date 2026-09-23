package com.dbcompanion.repository;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.common.exception.MetadataEditException;
import java.io.StringReader;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.dbcompanion.common.db.AiHistorySql.*;

/** Shared storage only. Transactions and session/schema binding remain in services. */
@Repository
public class AiHistoryRepository {
    private final JdbcTemplate jdbc;
    public AiHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);
    }
    private static MetadataEditException mismatch(String name) {
        return new MetadataEditException(409,"History asset mismatch",name+UiMessages.text("ui.05bc4cae6035", " · 기존 구조/원문이 다릅니다. 기존 이력을 변경하지 않았습니다."));
    }
    private String marker(String schema,String name,List<String> columns,Set<String> keys,Set<String> markers) {
        var types=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=?",String.class,schema,name);
        if(types.isEmpty())return null;
        if(!types.equals(List.of("TABLE")))throw mismatch(name);
        var comments=jdbc.queryForList("SELECT COMMENTS FROM SYS.ALL_TAB_COMMENTS WHERE OWNER=? AND TABLE_NAME=? AND TABLE_TYPE='TABLE'",String.class,schema,name);
        if(comments.size()!=1||comments.getFirst()==null||!markers.contains(comments.getFirst()))throw mismatch(name);
        var actual=jdbc.query("SELECT COLUMN_NAME,DATA_TYPE,NULLABLE FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",
                (r,n)->r.getString(1)+":"+r.getString(2)+":"+r.getString(3),schema,name);
        if(!actual.equals(columns))throw mismatch(name);
        var actualKeys=jdbc.query("""
                SELECT c.CONSTRAINT_TYPE,LISTAGG(k.COLUMN_NAME,',') WITHIN GROUP (ORDER BY k.POSITION)
                FROM SYS.ALL_CONSTRAINTS c JOIN SYS.ALL_CONS_COLUMNS k
                  ON k.OWNER=c.OWNER AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME AND k.TABLE_NAME=c.TABLE_NAME
                WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE IN ('P','U')
                  AND c.STATUS='ENABLED' AND c.VALIDATED='VALIDATED'
                GROUP BY c.CONSTRAINT_TYPE,c.CONSTRAINT_NAME
                """,(r,n)->r.getString(1)+":"+r.getString(2),schema,name);
        if(!new HashSet<>(actualKeys).equals(keys))throw mismatch(name);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TAB_IDENTITY_COLS WHERE OWNER=? AND TABLE_NAME=? AND COLUMN_NAME='SEQ' AND GENERATION_TYPE='ALWAYS'",Long.class,schema,name)!=1L)throw mismatch(name);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME=? AND STATUS='ENABLED'",Long.class,schema,name)!=0L)throw mismatch(name);
        return comments.getFirst();
    }
    public String status(String schema) {
        return marker(schema,TABLE,List.of("SEQ:NUMBER:N","OBJECT_TYPE:VARCHAR2:N","OBJECT_NAME:VARCHAR2:Y","OBJECT_ID:VARCHAR2:Y",
                "ATTRIBUTE_NAME:VARCHAR2:Y","ACTOR:VARCHAR2:N","EVENT_AT:VARCHAR2:N","ENTRY_KIND:VARCHAR2:N","OUTCOME:VARCHAR2:Y",
                "SOURCE_KEY:VARCHAR2:N","ITEM_NO:NUMBER:N","BEFORE_JSON:CLOB:Y","AFTER_JSON:CLOB:Y","PAYLOAD_JSON:CLOB:Y",
                "LEGACY_TABLE:VARCHAR2:Y","LEGACY_SEQ:NUMBER:Y","RECORDED_AT:TIMESTAMP(6) WITH TIME ZONE:N"),
                Set.of("P:SEQ","U:OBJECT_TYPE,SOURCE_KEY,ITEM_NO","U:LEGACY_TABLE,LEGACY_SEQ"),Set.of(MARKER,PENDING));
    }
    public boolean ready(String schema){return MARKER.equals(status(schema));}
    public void require(String schema) {
        if(!ready(schema))throw new MetadataEditException(409,"Common history required",UiMessages.text("ui.0aabadab75aa", "변경 이력에서 공통 보관 테이블을 준비해 주세요. 설정 변경은 실행하지 않았습니다."));
    }
    public boolean legacy(String schema,String name) {
        if(!LEGACY.contains(name))throw new IllegalArgumentException("Unknown history source");
        if(name.equals("DBC_PROFILE_HISTORY"))return marker(schema,name,List.of("SEQ:NUMBER:N","SOURCE_KEY:VARCHAR2:N","ITEM_NO:NUMBER:N",
                "PROFILE_NAME:VARCHAR2:Y","EVENT_AT:VARCHAR2:N","ACTOR:VARCHAR2:N","KIND:VARCHAR2:N","PAYLOAD:CLOB:N","RECORDED_AT:TIMESTAMP(6) WITH TIME ZONE:N"),
                Set.of("P:SEQ","U:SOURCE_KEY,ITEM_NO"),Set.of(ProfileHistorySql.MARKER))!=null;
        boolean team=name.equals("DBC_TEAM_EDIT_HISTORY");
        var columns=new ArrayList<>(List.of("SEQ:NUMBER:N","REQUEST_ID:VARCHAR2:N"));
        if(!team)columns.add("OBJECT_TYPE:VARCHAR2:N");
        columns.addAll(List.of((team?"TEAM_NAME":"OBJECT_NAME")+":VARCHAR2:N",(team?"TEAM_ID":"OBJECT_ID")+":VARCHAR2:Y",
                "ATTRIBUTE_NAME:VARCHAR2:N","ACTOR:VARCHAR2:N","EVENT_AT:TIMESTAMP(6) WITH TIME ZONE:N",
                "OUTCOME:VARCHAR2:N","BEFORE_JSON:CLOB:N","AFTER_JSON:CLOB:Y"));
        return marker(schema,name,columns,Set.of("P:SEQ","U:REQUEST_ID"),Set.of(team?"DB Manage Companion team edit history v1":"DB Manage Companion AI object edit history v1"))!=null;
    }
    public synchronized void install(String schema) {
        String state=status(schema);if(MARKER.equals(state))return;
        var sources=LEGACY.stream().filter(name->legacy(schema,name)).toList();
        if(state==null) {
            jdbc.execute(create(schema));
            jdbc.execute("COMMENT ON TABLE "+table(schema)+" IS '"+PENDING+"'");
        }
        status(schema);
        jdbc.execute("LOCK TABLE "+table(schema)+" IN EXCLUSIVE MODE NOWAIT");
        for(String name:sources)jdbc.execute("LOCK TABLE "+ProfileHistorySql.object(schema,name)+" IN SHARE MODE NOWAIT");
        for(String name:sources) {
            jdbc.update(copy(schema,name));
            if(jdbc.queryForObject(differences(schema,name),Long.class)!=0L)throw mismatch(name);
            Long original=jdbc.queryForObject("SELECT COUNT(*) FROM "+ProfileHistorySql.object(schema,name),Long.class);
            Long copied=jdbc.queryForObject("SELECT COUNT(*) FROM "+table(schema)+" WHERE LEGACY_TABLE=?",Long.class,name);
            if(!Objects.equals(original,copied))throw mismatch(name);
        }
        // Only publish after all rows and full CLOBs match. Oracle DDL commits the verified copy.
        jdbc.execute("COMMENT ON TABLE "+table(schema)+" IS '"+MARKER+"'");
        require(schema);
    }
    private static void kind(String kind){if(!Set.of("PROFILE","TEAM","AGENT","TASK","TOOL").contains(kind))throw new IllegalArgumentException("Invalid history kind");}
    public String before(String schema,String kind,String name,String id,String attribute,String actor,String payload) {
        kind(kind);String key=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO "+table(schema)+" (OBJECT_TYPE,OBJECT_NAME,OBJECT_ID,ATTRIBUTE_NAME,ACTOR,EVENT_AT,ENTRY_KIND,OUTCOME,SOURCE_KEY,ITEM_NO,BEFORE_JSON) VALUES (?,?,?,?,?,"+NOW+",'EDIT','BEFORE_SAVED',?,0,?)",s->{
            s.setString(1,kind);s.setString(2,name);s.setString(3,id);s.setString(4,attribute);s.setString(5,actor);s.setString(6,key);s.setCharacterStream(7,new StringReader(payload),payload.length());});
        return key;
    }
    public void after(String schema,String kind,String name,String key,String outcome,String payload) {
        kind(kind);if(!Set.of("VERIFIED","UNCERTAIN").contains(outcome))throw new IllegalArgumentException("Invalid history outcome");
        int count=jdbc.update("UPDATE "+table(schema)+" SET AFTER_JSON=?,OUTCOME=? WHERE OBJECT_TYPE=? AND OBJECT_NAME=? AND SOURCE_KEY=? AND ITEM_NO=0 AND ENTRY_KIND='EDIT' AND OUTCOME='BEFORE_SAVED'",s->{
            s.setCharacterStream(1,new StringReader(payload),payload.length());s.setString(2,outcome);s.setString(3,kind);s.setString(4,name);s.setString(5,key);});
        if(count!=1)throw mismatch(TABLE);
    }
}
