package com.dbcompanion.repository;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.db.FeedbackTrackingSql.Target;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AiFeedback;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackTrackingRepository {
    private final JdbcTemplate jdbc;
    private final AiHistoryRepository history;
    public FeedbackTrackingRepository(JdbcTemplate jdbc,AiHistoryRepository history) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);this.history=history;
    }
    public record Check(String name,String expression,String status,String validated) {}
    public record Trigger(String status,String validity,String source,boolean compatible) {}
    public record Entry(String seq,String profileId,String eventAt,String actor,String operation,String beforeJson,String afterJson) {}
    public record Page(List<Entry> entries,int page,boolean more) {}
    private static MetadataEditException conflict(String message) {return new MetadataEditException(409,"Feedback tracking conflict",message);}
    public Set<String> privileges() {return Set.copyOf(jdbc.queryForList("SELECT PRIVILEGE FROM SESSION_PRIVS WHERE PRIVILEGE IN ('CREATE TABLE','CREATE TRIGGER')",String.class));}
    public String archiveState(String schema) {
        String status=history.status(schema);
        if(!AiHistorySql.MARKER.equals(status))return "PREPARE";
        var checks=checks(schema);validateChecks(checks);
        if(tableRowsReady(checks))return "READY";
        return FeedbackTrackingSql.expansions().stream().allMatch(e->checks.stream().anyMatch(c->same(c.expression(),e.newExpression()))
                &&checks.stream().noneMatch(c->same(c.expression(),e.oldExpression())))?"READY":"UPGRADE";
    }
    public List<Check> checks(String schema) {return jdbc.query("SELECT CONSTRAINT_NAME,SEARCH_CONDITION_VC,STATUS,VALIDATED FROM SYS.ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME='DBC_AI_HISTORY' AND CONSTRAINT_TYPE='C'",
            (r,n)->new Check(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),schema);}
    private static boolean same(String a,String b){return FeedbackTrackingSql.condition(a).equals(FeedbackTrackingSql.condition(b));}
    public static boolean tableRowsReady(List<Check> checks) {
        return TableRowHistorySql.expansions().stream().allMatch(e->checks.stream().anyMatch(c->same(c.expression(),e.newExpression())))
                &&checks.stream().anyMatch(c->same(c.expression(),FeedbackTrackingSql.expansions().get(1).newExpression()))
                &&FeedbackTrackingSql.expansions().stream().noneMatch(e->checks.stream().anyMatch(c->same(c.expression(),e.oldExpression())))
                &&TableRowHistorySql.expansions().stream().noneMatch(e->checks.stream().anyMatch(c->same(c.expression(),e.oldExpression())));
    }
    private static List<FeedbackTrackingSql.Expansion> knownExpansions() {
        var all=new ArrayList<>(FeedbackTrackingSql.expansions());all.addAll(TableRowHistorySql.expansions());return all;
    }
    public static void validateChecks(List<Check> checks) {
        for(var c:checks) {
            String expression=FeedbackTrackingSql.condition(c.expression());
            if(!expression.contains("OBJECT_TYPE")&&!expression.contains("ENTRY_KIND"))continue;
            if(expression.equals("OBJECT_TYPEISNOTNULL")||expression.equals("ENTRY_KINDISNOTNULL"))continue;
            if(!"ENABLED".equals(c.status())||!"VALIDATED".equals(c.validated())||knownExpansions().stream()
                    .noneMatch(e->same(c.expression(),e.oldExpression())||same(c.expression(),e.newExpression())))
                throw conflict(c.name()+UiMessages.text("ui.9b24fa701f52", " · 공통 이력 제약이 예상과 다릅니다. 자동 변경하지 않습니다."));
        }
        for(var expansion:FeedbackTrackingSql.expansions()) {
            if(checks.stream().noneMatch(c->same(c.expression(),expansion.oldExpression())||same(c.expression(),expansion.newExpression())
                    ||TableRowHistorySql.expansions().stream().anyMatch(e->same(e.oldExpression(),expansion.newExpression())&&same(c.expression(),e.newExpression()))))
                throw conflict(UiMessages.text("ui.84072c6919c0", "공통 이력의 필수 제약을 확인할 수 없습니다."));
            if(checks.stream().anyMatch(c->c.name().equals(expansion.name())&&!same(c.expression(),expansion.newExpression())))
                throw conflict(expansion.name()+UiMessages.text("ui.a3c4ab6948c1", " · 같은 이름의 다른 제약이 있습니다."));
        }
        for(var expansion:TableRowHistorySql.expansions())if(checks.stream().anyMatch(c->c.name().equals(expansion.name())&&!same(c.expression(),expansion.newExpression())))
            throw conflict(expansion.name()+" · Constraint name conflict");
    }
    public void prepare(String schema) {
        history.install(schema);var initialChecks=checks(schema);validateChecks(initialChecks);
        if(tableRowsReady(initialChecks))return;
        // Add every wider constraint first. Never remove the only validating constraint.
        for(var e:FeedbackTrackingSql.expansions())if(initialChecks.stream().noneMatch(c->same(c.expression(),e.newExpression()))) {
            if(!jdbc.queryForList("SELECT TABLE_NAME FROM SYS.ALL_CONSTRAINTS WHERE OWNER=? AND CONSTRAINT_NAME=?",String.class,schema,e.name()).isEmpty())
                throw conflict(e.name()+UiMessages.text("ui.cdd0c9cd1ea3", " · 제약 이름이 이미 사용 중입니다."));
            jdbc.execute("ALTER TABLE "+AiHistorySql.table(schema)+" ADD CONSTRAINT "+MetadataSql.identifier(e.name())+" CHECK ("+e.newExpression()+") ENABLE VALIDATE");
        }
        var checks=checks(schema);validateChecks(checks);
        for(var e:FeedbackTrackingSql.expansions()) {
            if(checks.stream().noneMatch(c->same(c.expression(),e.newExpression())))throw conflict(UiMessages.text("ui.0b257497fa06", "확장 제약 검증이 완료되지 않았습니다."));
            for(var c:checks)if(same(c.expression(),e.oldExpression()))
                jdbc.execute("ALTER TABLE "+AiHistorySql.table(schema)+" DROP CONSTRAINT "+MetadataSql.identifier(c.name()));
        }
        if(!archiveState(schema).equals("READY"))throw conflict(UiMessages.text("ui.442061123009", "공통 이력 준비 결과를 확인해 주세요."));
    }
    public Long tableId(String schema,String profile) {
        var ids=jdbc.queryForList("SELECT OBJECT_ID FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND OBJECT_TYPE='TABLE'",Long.class,schema,AiFeedback.tableName(profile));
        if(ids.size()>1)throw conflict(UiMessages.text("ui.a1b0779a2ac8", "Feedback 테이블을 하나로 식별할 수 없습니다."));return ids.isEmpty()?null:ids.getFirst();
    }
    public void requireShape(Target t) {
        var columns=jdbc.query("SELECT COLUMN_NAME,DATA_TYPE FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",
                (r,n)->r.getString(1)+":"+r.getString(2),t.schema(),AiFeedback.tableName(t.profile()));
        if(!columns.equals(List.of("CONTENT:CLOB","ATTRIBUTES:JSON","EMBEDDING:VECTOR")))throw conflict(UiMessages.text("ui.b70b91a3a3ee", "Feedback 테이블 구조가 예상과 다릅니다. 설치하지 않았습니다."));
    }
    public Trigger trigger(Target t) {
        String name=FeedbackTrackingSql.triggerName(t);
        var other=jdbc.queryForList("SELECT TRIGGER_NAME FROM SYS.ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME=? AND SUBSTR(TRIGGER_NAME,1,7)='DBC_FH_' AND TRIGGER_NAME<>?",
                String.class,t.schema(),AiFeedback.tableName(t.profile()),name);
        if(!other.isEmpty())throw conflict(UiMessages.text("ui.665c0e8743a0", "다른 버전/대상의 이력 트리거가 있습니다: ")+String.join(", ",other)+UiMessages.text("ui.b21bec406299", ". 중복 설치하지 않습니다."));
        var objects=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=?",String.class,t.schema(),name);
        if(objects.isEmpty())return null;
        if(!objects.equals(List.of("TRIGGER")))throw conflict(name+UiMessages.text("ui.c7b3f17832e7", " · 다른 객체가 같은 이름을 사용합니다."));
        String source=String.join("",jdbc.queryForList("SELECT TEXT FROM SYS.ALL_SOURCE WHERE OWNER=? AND NAME=? AND TYPE='TRIGGER' ORDER BY LINE",String.class,t.schema(),name));
        var rows=jdbc.query("SELECT t.STATUS,o.STATUS,t.TABLE_OWNER,t.TABLE_NAME,t.TRIGGER_TYPE,t.TRIGGERING_EVENT,t.WHEN_CLAUSE FROM SYS.ALL_TRIGGERS t JOIN SYS.ALL_OBJECTS o ON o.OWNER=t.OWNER AND o.OBJECT_NAME=t.TRIGGER_NAME AND o.OBJECT_TYPE='TRIGGER' WHERE t.OWNER=? AND t.TRIGGER_NAME=?",
                (r,n)->new Trigger(r.getString(1),r.getString(2),source,t.schema().equals(r.getString(3))&&AiFeedback.tableName(t.profile()).equals(r.getString(4))
                        &&"AFTER EACH ROW".equals(r.getString(5))&&Set.of(r.getString(6).split(" OR ")).equals(Set.of("INSERT","UPDATE","DELETE"))
                        &&r.getString(7)==null&&FeedbackTrackingSql.sourceMatches(t,source)),t.schema(),name);
        if(rows.size()!=1)throw conflict(name+UiMessages.text("ui.282dbc8eea1a", " · 트리거 상태를 확인할 수 없습니다."));return rows.getFirst();
    }
    public void requireCompatible(Target t,boolean valid) {
        var trigger=trigger(t);
        if(trigger==null||!trigger.compatible())throw conflict(UiMessages.text("ui.94fa62d49483", "트리거 원문 또는 대상이 앱 버전과 다릅니다. 자동 교체하지 않습니다."));
        if(valid&&!"VALID".equals(trigger.validity())) {
            var errors=jdbc.query("SELECT LINE,POSITION,TEXT FROM SYS.ALL_ERRORS WHERE OWNER=? AND NAME=? AND TYPE='TRIGGER' ORDER BY SEQUENCE",
                    (r,n)->r.getInt(1)+":"+r.getInt(2)+" "+r.getString(3),t.schema(),FeedbackTrackingSql.triggerName(t));
            throw conflict(UiMessages.text("ui.1aa5accf6ca7", "트리거 컴파일 오류 · ")+String.join("\n",errors));
        }
    }
    public void create(Target t) {jdbc.execute(FeedbackTrackingSql.create(t));}
    public void toggle(Target t,boolean enabled) {jdbc.execute(FeedbackTrackingSql.switchSql(t,enabled));}
    public Page page(String schema,String profile,int page) {
        if(page<1||page>100000)throw new IllegalArgumentException("Invalid history page");
        if(!history.ready(schema))return new Page(List.of(),page,false);
        var rows=jdbc.query("SELECT SEQ,OBJECT_ID,EVENT_AT,ACTOR,ATTRIBUTE_NAME FROM "+AiHistorySql.table(schema)
                +" WHERE OBJECT_TYPE='FEEDBACK' AND OBJECT_NAME=? AND ENTRY_KIND='ROW_CHANGE' ORDER BY SEQ DESC OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY",
                (r,n)->new Entry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),null,null),profile,(page-1)*10);
        return new Page(List.copyOf(rows.subList(0,Math.min(10,rows.size()))),page,rows.size()>10);
    }
    public Entry entry(String schema,String profile,String seq) {
        if(seq==null||!seq.matches("[1-9][0-9]{0,37}"))throw new IllegalArgumentException("Invalid history sequence");
        history.require(schema);
        var rows=jdbc.query("SELECT SEQ,OBJECT_ID,EVENT_AT,ACTOR,ATTRIBUTE_NAME,BEFORE_JSON,AFTER_JSON FROM "+AiHistorySql.table(schema)
                +" WHERE OBJECT_TYPE='FEEDBACK' AND OBJECT_NAME=? AND ENTRY_KIND='ROW_CHANGE' AND SEQ=?",
                (r,n)->new Entry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)),profile,seq);
        if(rows.size()!=1)throw new MetadataEditException(404,"History not found",UiMessages.text("ui.7fec9133b43a", "이력을 찾을 수 없습니다."));return rows.getFirst();
    }
}
