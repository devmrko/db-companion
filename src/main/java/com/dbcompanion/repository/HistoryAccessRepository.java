package com.dbcompanion.repository;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.HistoryAccess.*;
import com.dbcompanion.model.MetadataEdit.Target;
import com.dbcompanion.model.MetadataHistory;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Fixed Oracle capability package. No credentials or arbitrary SQL are accepted by delegated users. */
public class HistoryAccessRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    public HistoryAccessRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public String user() { return jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV','SESSION_USER') FROM SYS.DUAL",String.class); }
    public String requireAdministrator() {
        var privileges=jdbc.queryForList("SELECT PRIVILEGE FROM SESSION_PRIVS",String.class);
        if(!privileges.contains("ADMINISTER DATABASE TRIGGER")) throw denied();
        return user();
    }
    public static MetadataEditException denied() {
        return new MetadataEditException(403,"Scoped history access denied",UiMessages.text("history.access.denied","이 대상의 이력 조회·관리 권한이 없습니다."));
    }
    public boolean exists(String owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND OBJECT_TYPE='PACKAGE'",Integer.class,owner,HistoryAccessSql.PACKAGE)==1;
    }
    public Inspection inspect(String owner,Target target) {
        String raw=jdbc.queryForObject("SELECT "+HistorySql.qualified(owner,HistoryAccessSql.PACKAGE)+".inspect(?,?) FROM SYS.DUAL",String.class,target.schema(),target.table());
        // Oracle SQL can turn a PL/SQL NO_DATA_FOUND from an unenrolled target into NULL.
        if(raw==null) return null;
        var result=JSON.readValue(raw,Inspection.class);
        if(!owner.equals(result.owner())||!target.schema().equals(result.schema())||!target.table().equals(result.table())
                ||!HistorySql.triggerName(target,true).equals(result.beforeTrigger())||!HistorySql.triggerName(target,false).equals(result.afterTrigger()))
            throw new MetadataEditException(409,"History inspection target mismatch",UiMessages.text("history.access.changed","이력 설정이 변경되었습니다. 관리 계정으로 다시 확인해 주세요."));
        return result;
    }
    public MetadataHistory.State state(Inspection i) {
        return new MetadataHistory.State(true,"Y".equals(i.enabled()),i.healthy()==1,
            UiMessages.text(i.healthy()!=1?"history.access.changed":"Y".equals(i.enabled())?"history.summary.active":"history.summary.off",
                i.healthy()!=1?"이력 설정이 변경되었습니다. 관리 계정으로 다시 확인해 주세요.":"Y".equals(i.enabled())?"이력 수집 중":"준비 완료 · 수집 꺼짐"),
            i.beforeTrigger(),i.afterTrigger(),i.owner(),i.canManage()==1,UiMessages.text("history.access.scoped","객체별 위임 권한 · 설치·코드 교체 권한은 포함하지 않습니다."));
    }
    public MetadataHistory.Page history(String owner,Target target,int page) {
        String raw;
        try { raw=jdbc.queryForObject("SELECT "+HistorySql.qualified(owner,HistoryAccessSql.PACKAGE)+".entries(?,?,?) FROM SYS.DUAL",String.class,target.schema(),target.table(),page); }
        catch(org.springframework.dao.DataAccessException ex) {
            for(Throwable cause=ex;cause!=null;cause=cause.getCause()) if(cause instanceof java.sql.SQLException sql&&sql.getErrorCode()==20086) throw denied();
            throw ex;
        }
        var root=JSON.readTree(raw);
        if(!root.isArray()||root.size()>11) throw incompatible();
        var rows=new ArrayList<MetadataHistory.Change>();
        for(var node:root) {
            var object=(tools.jackson.databind.node.ObjectNode)node;
            // Oracle may embed IS JSON CLOB columns as JSON values instead of JSON strings.
            for(String key:List.of("beforeJson","afterJson")) {
                var value=object.path(key);
                if(!value.isString()) object.put(key,JSON.writeValueAsString(value));
            }
            rows.add(JSON.treeToValue(object,MetadataHistory.Change.class));
        }
        return new MetadataHistory.Page(List.copyOf(rows.subList(0,Math.min(10,rows.size()))),page,rows.size()>10);
    }
    public void toggle(String owner,Target target,boolean enabled) {
        jdbc.update("BEGIN "+HistorySql.qualified(owner,HistoryAccessSql.PACKAGE)+".set_enabled(?,?,?); END;",target.schema(),target.table(),enabled?"Y":"N");
    }
    public List<String> users() {
        return jdbc.queryForList("SELECT USERNAME FROM SYS.DBA_USERS WHERE ORACLE_MAINTAINED='N' AND COMMON='NO' ORDER BY USERNAME",String.class);
    }
    public List<Grant> grants(String owner,Target target) {
        if(!exists(owner)) return List.of();
        return jdbc.query("SELECT GRANTEE,CAN_READ,CAN_MANAGE,TO_CHAR(CHANGED_AT,'YYYY-MM-DD HH24:MI:SS TZH:TZM'),CHANGED_BY FROM "+HistorySql.qualified(owner,"DBC_MH_ACCESS")
            +" WHERE SCHEMA_NAME=? AND TABLE_NAME=? ORDER BY GRANTEE",(r,n)->new Grant(r.getString(1),"Y".equals(r.getString(2)),"Y".equals(r.getString(3)),r.getString(4),r.getString(5)),target.schema(),target.table());
    }
    /** Validate all existing assets before any CREATE. Unknown installations are never overwritten. */
    public List<HistorySql.Asset> missing(String owner,MetadataHistoryRepository history) {
        var missing=new ArrayList<HistorySql.Asset>();
        for(var asset:HistoryAccessSql.assets(owner)) {
            var types=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=?",String.class,owner,asset.name());
            if(types.isEmpty()||asset.type().equals("PACKAGE BODY")&&types.equals(List.of("PACKAGE"))) { missing.add(asset); continue; }
            if(!types.contains(asset.type())) throw incompatible();
            if(asset.type().equals("TABLE")) {
                var columns=jdbc.queryForList("SELECT COLUMN_NAME||':'||DATA_TYPE||':'||NULLABLE FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",String.class,owner,asset.name());
                var expected=asset.name().equals("DBC_MH_TARGETS")?
                    List.of("SCHEMA_NAME:VARCHAR2:N","TABLE_NAME:VARCHAR2:N","BEFORE_TRIGGER:VARCHAR2:N","AFTER_TRIGGER:VARCHAR2:N","BEFORE_SOURCE:CLOB:N","AFTER_SOURCE:CLOB:N","SPEC_SOURCE:CLOB:N","BODY_SOURCE:CLOB:N"):
                    List.of("SCHEMA_NAME:VARCHAR2:N","TABLE_NAME:VARCHAR2:N","GRANTEE:VARCHAR2:N","CAN_READ:CHAR:N","CAN_MANAGE:CHAR:N","CHANGED_AT:TIMESTAMP(6) WITH TIME ZONE:N","CHANGED_BY:VARCHAR2:N");
                if(!columns.equals(expected)) throw incompatible();
                var keys=jdbc.queryForList("SELECT cc.COLUMN_NAME FROM SYS.ALL_CONSTRAINTS c JOIN SYS.ALL_CONS_COLUMNS cc ON cc.OWNER=c.OWNER AND cc.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE='P' AND c.STATUS='ENABLED' AND c.VALIDATED='VALIDATED' ORDER BY cc.POSITION",String.class,owner,asset.name());
                if(!keys.equals(asset.name().equals("DBC_MH_TARGETS")?List.of("SCHEMA_NAME","TABLE_NAME"):List.of("SCHEMA_NAME","TABLE_NAME","GRANTEE"))) throw incompatible();
            } else if(!HistorySql.sourceMatches(asset,history.source(asset))) throw incompatible();
            history.requireValid(owner,asset);
        }
        return List.copyOf(missing);
    }
    private MetadataEditException incompatible() { return new MetadataEditException(409,"Unrecognized history access assets",UiMessages.text("history.access.incompatible","권한 관리 객체가 앱 정의와 다릅니다. 자동 교체하지 않습니다.")); }
    public void install(String owner,MetadataHistoryRepository history) {
        for(var asset:missing(owner,history)) { history.execute(asset.sql()); history.requireValid(owner,asset); }
    }
    public void register(String owner,Target target,MetadataHistoryRepository history) {
        var config=history.configuration(target);
        if(!owner.equals(config.triggerOwner())) throw incompatible();
        var sources=new ArrayList<String>();
        for(var asset:List.of(HistorySql.trigger(target,owner,true),HistorySql.trigger(target,owner,false),
                HistorySql.assets(target,owner).stream().filter(a->a.type().equals("PACKAGE")).findFirst().orElseThrow(),HistorySql.auditBody(target,false))) {
            history.validateAsset(target,asset); sources.add(history.source(asset));
        }
        jdbc.update("MERGE INTO "+HistorySql.qualified(owner,"DBC_MH_TARGETS")+" t USING (SELECT ? SCHEMA_NAME,? TABLE_NAME FROM SYS.DUAL) s ON (t.SCHEMA_NAME=s.SCHEMA_NAME AND t.TABLE_NAME=s.TABLE_NAME) "
            +"WHEN MATCHED THEN UPDATE SET BEFORE_SOURCE=?,AFTER_SOURCE=?,SPEC_SOURCE=?,BODY_SOURCE=? "
            +"WHEN NOT MATCHED THEN INSERT (SCHEMA_NAME,TABLE_NAME,BEFORE_TRIGGER,AFTER_TRIGGER,BEFORE_SOURCE,AFTER_SOURCE,SPEC_SOURCE,BODY_SOURCE) VALUES (s.SCHEMA_NAME,s.TABLE_NAME,?,?,?,?,?,?)",
            target.schema(),target.table(),sources.get(0),sources.get(1),sources.get(2),sources.get(3),HistorySql.triggerName(target,true),HistorySql.triggerName(target,false),sources.get(0),sources.get(1),sources.get(2),sources.get(3));
    }
    public void grant(String owner,Target target,String user,Operation operation) {
        if(operation==Operation.INHERIT) {
            jdbc.update("DELETE FROM "+HistorySql.qualified(owner,"DBC_MH_ACCESS")+" WHERE SCHEMA_NAME=? AND TABLE_NAME=? AND GRANTEE=?",target.schema(),target.table(),user); return;
        }
        boolean read=operation==Operation.READ||operation==Operation.MANAGE,manage=operation==Operation.MANAGE;
        jdbc.update("MERGE INTO "+HistorySql.qualified(owner,"DBC_MH_ACCESS")+" t USING (SELECT ? SCHEMA_NAME,? TABLE_NAME,? GRANTEE FROM SYS.DUAL) s ON (t.SCHEMA_NAME=s.SCHEMA_NAME AND t.TABLE_NAME=s.TABLE_NAME AND t.GRANTEE=s.GRANTEE) "
            +"WHEN MATCHED THEN UPDATE SET CAN_READ=?,CAN_MANAGE=?,CHANGED_AT=SYSTIMESTAMP,CHANGED_BY=SYS_CONTEXT('USERENV','SESSION_USER') "
            +"WHEN NOT MATCHED THEN INSERT (SCHEMA_NAME,TABLE_NAME,GRANTEE,CAN_READ,CAN_MANAGE,CHANGED_AT,CHANGED_BY) VALUES (s.SCHEMA_NAME,s.TABLE_NAME,s.GRANTEE,?,?,SYSTIMESTAMP,SYS_CONTEXT('USERENV','SESSION_USER'))",
            target.schema(),target.table(),user,read?"Y":"N",manage?"Y":"N",read?"Y":"N",manage?"Y":"N");
        // Only the fixed capability entry point and installer discovery, never business data or broad DDL authority.
        if(read) {
            jdbc.execute("GRANT EXECUTE ON "+HistorySql.qualified(owner,HistoryAccessSql.PACKAGE)+" TO "+HistoryAccessSql.grantee(user));
            if(!target.schema().equals(user)) jdbc.execute("GRANT READ ON "+HistorySql.qualified(target.schema(),"DBC_METADATA_TRACKING")+" TO "+HistoryAccessSql.grantee(user));
        }
    }
}
