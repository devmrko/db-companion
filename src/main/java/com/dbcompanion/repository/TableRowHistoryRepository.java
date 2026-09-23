package com.dbcompanion.repository;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.db.TableRowHistorySql.Target;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.TableRowHistory.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TableRowHistoryRepository {
    private final JdbcTemplate jdbc;
    private final VectorSearchRepository catalog;
    private final AiHistoryRepository history;
    private final FeedbackTrackingRepository archives;
    public TableRowHistoryRepository(JdbcTemplate jdbc,VectorSearchRepository catalog,AiHistoryRepository history,FeedbackTrackingRepository archives) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);
        this.catalog=catalog;this.history=history;this.archives=archives;
    }
    public static MetadataEditException conflict(String message){return new MetadataEditException(409,"Table history conflict",message);}
    public Target target(String schema,String table) {
        catalog.columns(schema,table);
        var ids=jdbc.queryForList("SELECT OBJECT_ID FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND OBJECT_TYPE='TABLE'",Long.class,schema,table);
        if(ids.size()!=1)throw conflict("Table identity is unavailable");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_MVIEWS WHERE OWNER=? AND MVIEW_NAME=?",Integer.class,schema,table)!=0)
            throw conflict("Materialized views are not supported for row history");
        var columns=jdbc.query("SELECT COLUMN_NAME,DATA_TYPE,VIRTUAL_COLUMN FROM SYS.ALL_TAB_COLS WHERE OWNER=? AND TABLE_NAME=? AND USER_GENERATED='YES' ORDER BY INTERNAL_COLUMN_ID",
                (r,n)->new TableRowHistorySql.Column(r.getString(1),r.getString(2),"YES".equals(r.getString(3))),schema,table);
        return new Target(schema,table,ids.getFirst(),columns);
    }
    public Set<String> privileges(){return archives.privileges();}
    public String archiveState(String schema) {
        if(!history.ready(schema))return "PREPARE";
        var checks=archives.checks(schema);FeedbackTrackingRepository.validateChecks(checks);
        return FeedbackTrackingRepository.tableRowsReady(checks)?"READY":"UPGRADE";
    }
    private static boolean same(String a,String b){return FeedbackTrackingSql.condition(a).equals(FeedbackTrackingSql.condition(b));}
    public synchronized void prepare(String schema) {
        // Existing creation/migration and Feedback-compatible expansion preserve earlier records.
        archives.prepare(schema);
        var before=archives.checks(schema);FeedbackTrackingRepository.validateChecks(before);
        for(var e:TableRowHistorySql.expansions())if(before.stream().noneMatch(c->same(c.expression(),e.newExpression()))) {
            if(!jdbc.queryForList("SELECT CONSTRAINT_NAME FROM SYS.ALL_CONSTRAINTS WHERE OWNER=? AND CONSTRAINT_NAME=?",String.class,schema,e.name()).isEmpty())
                throw conflict("Constraint name already exists: "+e.name());
            jdbc.execute("ALTER TABLE "+AiHistorySql.table(schema)+" ADD CONSTRAINT "+com.dbcompanion.model.VectorSearch.quote(e.name())+" CHECK ("+e.newExpression()+") ENABLE VALIDATE");
        }
        var after=archives.checks(schema);FeedbackTrackingRepository.validateChecks(after);
        for(var e:TableRowHistorySql.expansions()) {
            if(after.stream().noneMatch(c->same(c.expression(),e.newExpression())))throw conflict("Expanded history constraint is missing");
            for(var c:after)if(same(c.expression(),e.oldExpression()))jdbc.execute("ALTER TABLE "+AiHistorySql.table(schema)+" DROP CONSTRAINT "+com.dbcompanion.model.VectorSearch.quote(c.name()));
        }
        if(!archiveState(schema).equals("READY"))throw conflict("History archive is not ready");
    }
    public record Trigger(String status,String validity,boolean compatible) {}
    public Trigger trigger(Target t) {
        String name=TableRowHistorySql.triggerName(t);
        var others=jdbc.queryForList("SELECT TRIGGER_NAME FROM SYS.ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME=? AND SUBSTR(TRIGGER_NAME,1,7)='DBC_RH_' AND TRIGGER_NAME<>?",
                String.class,t.schema(),t.table(),name);
        if(!others.isEmpty())throw conflict("Another row history trigger exists: "+String.join(", ",others));
        var objects=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=?",String.class,t.schema(),name);
        if(objects.isEmpty())return null;
        if(!objects.equals(List.of("TRIGGER")))throw conflict("Object name conflict: "+name);
        String source=String.join("",jdbc.queryForList("SELECT TEXT FROM SYS.ALL_SOURCE WHERE OWNER=? AND NAME=? AND TYPE='TRIGGER' ORDER BY LINE",String.class,t.schema(),name));
        var found=jdbc.query("SELECT t.STATUS,o.STATUS,t.TABLE_OWNER,t.TABLE_NAME,t.TRIGGER_TYPE,t.TRIGGERING_EVENT,t.WHEN_CLAUSE FROM SYS.ALL_TRIGGERS t JOIN SYS.ALL_OBJECTS o ON o.OWNER=t.OWNER AND o.OBJECT_NAME=t.TRIGGER_NAME AND o.OBJECT_TYPE='TRIGGER' WHERE t.OWNER=? AND t.TRIGGER_NAME=?",
                (r,n)->new Trigger(r.getString(1),r.getString(2),t.schema().equals(r.getString(3))&&t.table().equals(r.getString(4))
                        &&"COMPOUND".equals(r.getString(5))&&Set.of(r.getString(6).split(" OR ")).equals(Set.of("INSERT","UPDATE","DELETE"))
                        &&r.getString(7)==null&&t.supported()&&TableRowHistorySql.sourceMatches(t,source)),t.schema(),name);
        if(found.size()!=1)throw conflict("Trigger state is unavailable: "+name);return found.getFirst();
    }
    public void requireCompatible(Target t,boolean valid) {
        var trigger=trigger(t);
        if(trigger==null||!trigger.compatible())throw conflict("Trigger code or table columns differ; automatic replacement is disabled");
        if(valid&&!trigger.validity().equals("VALID")) {
            var errors=jdbc.query("SELECT LINE,POSITION,TEXT FROM SYS.ALL_ERRORS WHERE OWNER=? AND NAME=? AND TYPE='TRIGGER' ORDER BY SEQUENCE",
                    (r,n)->r.getInt(1)+":"+r.getInt(2)+" "+r.getString(3),t.schema(),TableRowHistorySql.triggerName(t));
            throw conflict("Trigger compilation: "+String.join("\n",errors));
        }
    }
    public void create(Target t){jdbc.execute(TableRowHistorySql.create(t));}
    public void toggle(Target t,boolean enabled){jdbc.execute(TableRowHistorySql.switchSql(t,enabled));}
    public Page page(String schema,String table,int page) {
        if(page<1||page>100_000)throw new IllegalArgumentException("Invalid history page");
        if(!history.ready(schema))return new Page(List.of(),page,false);
        var rows=jdbc.query("SELECT SEQ,OBJECT_ID,EVENT_AT,ACTOR,ATTRIBUTE_NAME FROM "+AiHistorySql.table(schema)
                +" WHERE OBJECT_TYPE='TABLE' AND OBJECT_NAME=? AND ENTRY_KIND='ROW_CHANGE' ORDER BY SEQ DESC OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY",
                (r,n)->new Entry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),null,null),table,(page-1)*10);
        return new Page(rows.subList(0,Math.min(rows.size(),10)),page,rows.size()>10);
    }
    public Entry entry(String schema,String table,String seq) {
        if(seq==null||!seq.matches("[1-9][0-9]{0,37}"))throw new IllegalArgumentException("Invalid history sequence");
        history.require(schema);
        var entries=jdbc.query("SELECT SEQ,OBJECT_ID,EVENT_AT,ACTOR,ATTRIBUTE_NAME,BEFORE_JSON,AFTER_JSON FROM "+AiHistorySql.table(schema)
                +" WHERE OBJECT_TYPE='TABLE' AND OBJECT_NAME=? AND ENTRY_KIND='ROW_CHANGE' AND SEQ=?",
                (r,n)->new Entry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)),table,seq);
        if(entries.size()!=1)throw new MetadataEditException(404,"History not found","History not found");return entries.getFirst();
    }
}
