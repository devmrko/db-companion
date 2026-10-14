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
    public Term enrich(String owner,Term term){
        return enrich(owner,List.of(term)).getFirst();
    }
    /** Read a recorded revision, including a pre-history snapshot recorded as before. */
    public Term revision(String owner,String id,long revision){
        BusinessGlossary.id(id);if(revision<1)throw BusinessGlossary.invalid();require(owner);
        var rows=jdbc.query("SELECT PAYLOAD FROM "+AppRecordSql.table(owner)
                +" WHERE RECORD_TYPE=? AND STATE='SUCCEEDED' AND JSON_VALUE(PAYLOAD,'$.termId')=?"
                +" AND (JSON_VALUE(PAYLOAD,'$.after.revision' RETURNING NUMBER)=? OR JSON_VALUE(PAYLOAD,'$.before.revision' RETURNING NUMBER)=?)"
                +" ORDER BY SEQ DESC FETCH FIRST 1 ROW ONLY",(r,n)->{
            var clob=r.getClob(1);try{
                if(clob==null||clob.length()>200_000)throw BusinessGlossary.invalid();
                var change=json.readValue(clob.getSubString(1,(int)clob.length()),Change.class);
                var term=change.after().revision()==revision?change.after():change.before();
                if(term==null||!id.equals(term.id())||term.revision()!=revision)throw BusinessGlossary.invalid();return term;
            }finally{if(clob!=null)clob.free();}
        },BusinessGlossaryHistory.TYPE,id,revision,revision);
        if(rows.size()!=1)throw BusinessGlossary.stale();return rows.getFirst();
    }
    public List<Term> enrich(String owner,List<Term> terms){
        if(terms.isEmpty()||!"READY".equals(status(owner)))return terms;
        var snapshots=new HashMap<String,Term>();
        for(int start=0;start<terms.size();start+=100){
            var batch=terms.subList(start,Math.min(start+100,terms.size()));var conditions=new ArrayList<String>();var args=new ArrayList<Object>();args.add(BusinessGlossaryHistory.TYPE);
            for(var term:batch){conditions.add("(JSON_VALUE(PAYLOAD,'$.termId')=? AND JSON_VALUE(PAYLOAD,'$.after.revision' RETURNING NUMBER)=?)");args.add(term.id());args.add(term.revision());}
            var rows=jdbc.queryForList("SELECT PAYLOAD FROM (SELECT PAYLOAD,ROW_NUMBER() OVER (PARTITION BY JSON_VALUE(PAYLOAD,'$.termId') ORDER BY SEQ DESC) RN FROM "+AppRecordSql.table(owner)
                    +" WHERE RECORD_TYPE=? AND STATE='SUCCEEDED' AND ("+String.join(" OR ",conditions)+")) WHERE RN=1",String.class,args.toArray());
            for(String payload:rows){if(payload.length()>200_000)throw BusinessGlossary.invalid();var change=json.readValue(payload,Change.class);var saved=change.after();
                if(!change.termId().equals(saved.id())||batch.stream().noneMatch(t->t.id().equals(saved.id())&&t.revision()==saved.revision()))throw BusinessGlossary.invalid();snapshots.put(saved.id(),saved);}
        }
        return terms.stream().map(term->snapshots.containsKey(term.id())?term.withRdf(snapshots.get(term.id()).rdf()):term).toList();
    }
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
