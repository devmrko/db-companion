package com.dbcompanion.repository;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.AiHistorySql;
import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.TeamEdit.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TeamHistoryRepository {
    private static final String LEGACY="DBC_TEAM_EDIT_HISTORY";
    private final JdbcTemplate jdbc;
    private final AiHistoryRepository common;
    public TeamHistoryRepository(JdbcTemplate jdbc,AiHistoryRepository common) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);this.common=common;
    }
    public boolean installed(String schema){return common.ready(schema);}
    public static String createSql(String schema){return AiHistorySql.create(schema);}
    public void install(String schema){common.install(schema);}
    public void require(String schema){common.require(schema);}
    public String before(Target target,String teamId,String actor,String payload) {
        return common.before(target.schema(),"TEAM",target.team(),teamId,target.attribute(),actor,payload);
    }
    public void after(Target target,String request,String outcome,String payload) {
        common.after(target.schema(),"TEAM",target.team(),request,outcome,payload);
    }
    public HistoryPage history(String schema,String team,int page) {
        boolean ready=installed(schema);
        if(!ready&&!common.legacy(schema,LEGACY))return new HistoryPage(false,List.of(),false);
        String table=ready?AiHistorySql.table(schema):ProfileHistorySql.object(schema,LEGACY);
        String filter=ready?"OBJECT_TYPE='TEAM' AND OBJECT_NAME=?":"TEAM_NAME=?";
        var rows=jdbc.query("SELECT SEQ,EVENT_AT,ACTOR,ATTRIBUTE_NAME,OUTCOME FROM "+table+" WHERE "+filter+" ORDER BY SEQ DESC OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY",
                (r,n)->new HistoryRow((ready?"":"L:")+r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),team,(page-1)*10);
        return new HistoryPage(ready,List.copyOf(rows.subList(0,Math.min(10,rows.size()))),rows.size()>10);
    }
    public HistoryEntry entry(String schema,String team,String seq) {
        boolean ready=!seq.startsWith("L:");
        if(ready)require(schema);
        if(!ready&&!common.legacy(schema,LEGACY))throw new MetadataEditException(404,"Team history not found",UiMessages.text("ui.7fec9133b43a", "이력을 찾을 수 없습니다."));
        String table=ready?AiHistorySql.table(schema):ProfileHistorySql.object(schema,LEGACY);
        String filter=ready?"OBJECT_TYPE='TEAM' AND OBJECT_NAME=?":"TEAM_NAME=?";
        var rows=jdbc.query("SELECT SEQ,EVENT_AT,ACTOR,ATTRIBUTE_NAME,OUTCOME,BEFORE_JSON,AFTER_JSON FROM "+table+" WHERE "+filter+" AND SEQ=?",
                (r,n)->new HistoryEntry((ready?"":"L:")+r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)),team,new java.math.BigDecimal(ready?seq:seq.substring(2)));
        if(rows.isEmpty())throw new MetadataEditException(404,"Team history not found",UiMessages.text("ui.7fec9133b43a", "이력을 찾을 수 없습니다."));return rows.getFirst();
    }
}
