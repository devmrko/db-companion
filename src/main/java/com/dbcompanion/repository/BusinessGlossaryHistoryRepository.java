package com.dbcompanion.repository;

import com.dbcompanion.common.db.AppRecordSql;
import com.dbcompanion.model.BusinessGlossary;
import com.dbcompanion.model.BusinessGlossary.Term;
import com.dbcompanion.model.BusinessGlossaryHistory;
import com.dbcompanion.model.BusinessGlossaryHistory.*;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class BusinessGlossaryHistoryRepository {
    private final JdbcTemplate jdbc;
    private final AppRecordRepository records;
    private final JsonMapper json;
    public BusinessGlossaryHistoryRepository(JdbcTemplate jdbc,AppRecordRepository records,JsonMapper json){
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);
        this.records=records;this.json=json;
    }
    public String status(String owner){return records.status(owner,owner);}
    public void require(String owner){
        if(!"READY".equals(status(owner)))throw BusinessGlossary.failure(409,"변경 이력 저장소를 준비해 주세요. 용어는 저장하지 않았습니다.");
    }
    public void install(String owner){records.install(owner,owner);require(owner);}
    /** Called inside the same transaction as the term INSERT/UPDATE; never commits independently. */
    public void append(String owner,Term before,Term after){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Glossary history requires the term transaction");
        var change=new Change(1,after.id(),before==null?"CREATE":"UPDATE",before,after);
        String payload=json.writeValueAsString(change);
        if(payload.length()>200_000)throw BusinessGlossary.invalid();
        jdbc.update("INSERT INTO "+AppRecordSql.table(owner)+" (RECORD_ID,RECORD_TYPE,STATE,PAYLOAD) VALUES (?,?,'SUCCEEDED',?)",s->{
            s.setString(1,UUID.randomUUID().toString());s.setString(2,BusinessGlossaryHistory.TYPE);
            s.setCharacterStream(3,new StringReader(payload),payload.length());
        });
    }
    public Page page(String owner,String id,String before){
        BusinessGlossary.id(id);String cursor=Objects.toString(before,"");
        if(!cursor.isEmpty()&&!cursor.matches("[1-9][0-9]{0,37}"))throw BusinessGlossary.invalid();
        require(owner);
        var args=new ArrayList<Object>(List.of(BusinessGlossaryHistory.TYPE,id));
        if(!cursor.isEmpty())args.add(new BigDecimal(cursor));
        var rows=jdbc.query("SELECT SEQ,RECORD_ID,ACTOR,RECORDED_AT,PAYLOAD FROM "+AppRecordSql.table(owner)
                +" WHERE RECORD_TYPE=? AND STATE='SUCCEEDED' AND JSON_VALUE(PAYLOAD,'$.termId')=?"
                +(cursor.isEmpty()?"":" AND SEQ<?")+" ORDER BY SEQ DESC FETCH FIRST 11 ROWS ONLY",(r,n)->{
            var clob=r.getClob(5);
            try{
                if(clob==null||clob.length()>200_000)throw BusinessGlossary.invalid();
                var change=json.readValue(clob.getSubString(1,(int)clob.length()),Change.class);
                if(!id.equals(change.termId()))throw BusinessGlossary.invalid();
                return new Entry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),change);
            }finally{if(clob!=null)clob.free();}
        },args.toArray());
        return new Page(rows.stream().limit(10).toList(),rows.size()>10?rows.get(9).seq():"");
    }
}
