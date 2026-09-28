package com.dbcompanion.repository;

import com.dbcompanion.model.BusinessGlossary;
import com.dbcompanion.model.BusinessGlossary.*;
import java.io.StringReader;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.common.db.BusinessGlossarySql.*;

@Repository
public class BusinessGlossaryRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public BusinessGlossaryRepository(JdbcTemplate jdbc,JsonMapper json){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(30);this.json=json;}
    public String tableStatus(String owner){
        var objects=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL",String.class,owner,TABLE);
        if(objects.isEmpty())return "MISSING";
        if(!objects.equals(List.of("TABLE")))return "MISMATCH";
        var columns=jdbc.query("SELECT COLUMN_NAME,DATA_TYPE,CHAR_LENGTH,NULLABLE FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",(r,n)->r.getString(1)+":"+r.getString(2)+":"+r.getInt(3)+":"+r.getString(4),owner,TABLE);
        if(!columns.equals(List.of("TERM_ID:VARCHAR2:36:N","REVISION:NUMBER:0:N","FORMAT_VERSION:NUMBER:0:N","TERM_TEXT:VARCHAR2:256:N","ALIASES_JSON:CLOB:0:N","DEFINITION_TEXT:CLOB:0:N","SQL_CRITERIA:CLOB:0:N","ENABLED_YN:VARCHAR2:1:N","SEARCH_TEXT:CLOB:0:N","UPDATED_AT:TIMESTAMP(6) WITH TIME ZONE:0:N")))return "MISMATCH";
        if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME=? AND STATUS='ENABLED'",Integer.class,owner,TABLE)!=0)return "MISMATCH";
        if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_EXTERNAL_TABLES WHERE OWNER=? AND TABLE_NAME=?",Integer.class,owner,TABLE)!=0)return "MISMATCH";
        var pk=jdbc.queryForList("SELECT k.COLUMN_NAME FROM SYS.ALL_CONSTRAINTS c JOIN SYS.ALL_CONS_COLUMNS k ON k.OWNER=c.OWNER AND k.TABLE_NAME=c.TABLE_NAME AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE='P' AND c.STATUS='ENABLED' AND c.VALIDATED='VALIDATED' ORDER BY k.POSITION",String.class,owner,TABLE);
        if(!pk.equals(List.of("TERM_ID")))return "MISMATCH";
        for(var check:Map.of("DBC_BT_V1","FORMAT_VERSION=1","DBC_BT_JSON","ALIASES_JSONISJSON","DBC_BT_ENABLED","ENABLED_YNIN'Y','N'").entrySet()){
            var values=jdbc.queryForList("SELECT SEARCH_CONDITION_VC FROM SYS.ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? AND CONSTRAINT_NAME=? AND STATUS='ENABLED' AND VALIDATED='VALIDATED'",String.class,owner,TABLE,check.getKey());
            if(values.size()!=1||!values.getFirst().replaceAll("[\\s\"()]","").equalsIgnoreCase(check.getValue()))return "MISMATCH";
        }
        return "READY";
    }
    public void requireTable(String owner){if(!tableStatus(owner).equals("READY"))throw BusinessGlossary.failure(409,"업무 용어 사전이 없거나 구조가 다릅니다. 관리 화면에서 상태를 확인해 주세요.");}
    public String textStatus(String owner){
        var lexer=jdbc.queryForList("SELECT PRE_OBJECT FROM CTXSYS.CTX_USER_PREFERENCES WHERE PRE_NAME=? AND PRE_CLASS='LEXER'",String.class,LEXER);
        var policy=jdbc.queryForList("SELECT IDX_NAME FROM CTXSYS.CTX_USER_INDEXES WHERE IDX_NAME=?",String.class,POLICY);
        var index=jdbc.queryForList("SELECT INDEX_NAME FROM SYS.ALL_INDEXES WHERE OWNER=? AND INDEX_NAME=?",String.class,owner,INDEX);
        if(lexer.isEmpty()&&policy.isEmpty()&&index.isEmpty())return "MISSING";
        if(!lexer.equals(List.of("KOREAN_MORPH_LEXER"))||policy.size()!=1||index.size()!=1)return "CHECK_REQUIRED";
        int valid=jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_INDEXES WHERE OWNER=? AND INDEX_NAME=? AND TABLE_OWNER=? AND TABLE_NAME=? AND ITYP_OWNER='CTXSYS' AND ITYP_NAME='CONTEXT' AND DOMIDX_STATUS='VALID' AND DOMIDX_OPSTATUS='VALID'",Integer.class,owner,INDEX,owner,TABLE);
        var cols=jdbc.queryForList("SELECT COLUMN_NAME FROM SYS.ALL_IND_COLUMNS WHERE INDEX_OWNER=? AND INDEX_NAME=? ORDER BY COLUMN_POSITION",String.class,owner,INDEX);
        if(valid!=1||!cols.equals(List.of("SEARCH_TEXT")))return "CHECK_REQUIRED";
        for(String name:List.of(POLICY,INDEX)){
            var types=jdbc.queryForList("SELECT IXO_OBJECT FROM CTXSYS.CTX_USER_INDEX_OBJECTS WHERE IXO_INDEX_NAME=? AND IXO_CLASS='LEXER'",String.class,name);
            if(!types.equals(List.of("KOREAN_MORPH_LEXER")))return "CHECK_REQUIRED";
        }
        return "READY";
    }
    public void install(String owner,String operation){
        if(operation.equals("TABLE")){
            if(!tableStatus(owner).equals("MISSING"))throw BusinessGlossary.stale();jdbc.execute(create(owner));requireTable(owner);
        }else if(operation.equals("TEXT")){
            requireTable(owner);if(!textStatus(owner).equals("MISSING"))throw BusinessGlossary.stale();
            for(String sql:textSetup(owner))jdbc.execute(sql);
            if(!textStatus(owner).equals("READY"))throw BusinessGlossary.failure(409,"Oracle Text 설정 확인이 필요합니다. 자동 재설치하거나 기존 객체를 삭제하지 않습니다.");
        }else throw BusinessGlossary.invalid();
    }
    private String clob(ResultSet r,String column,int max) throws SQLException {
        Clob c=r.getClob(column);if(c==null)throw BusinessGlossary.invalid();
        try{if(c.length()>max)throw BusinessGlossary.failure(413,"저장된 용어의 크기가 앱 한도를 초과합니다.");return c.getSubString(1,(int)c.length());}finally{c.free();}
    }
    private List<String> aliases(ResultSet r) throws SQLException {
        var node=json.readTree(clob(r,"ALIASES_JSON",16000));if(!node.isArray()||node.size()>20)throw BusinessGlossary.invalid();
        var result=new ArrayList<String>();for(var item:node){if(!item.isString())throw BusinessGlossary.invalid();result.add(BusinessGlossary.text(item.asString(),256,true));}return result;
    }
    private Term term(ResultSet r) throws SQLException {
        var term=new Term(r.getString("TERM_ID"),r.getLong("REVISION"),r.getString("TERM_TEXT"),aliases(r),clob(r,"DEFINITION_TEXT",4000),clob(r,"SQL_CRITERIA",4000),"Y".equals(r.getString("ENABLED_YN")),r.getString("UPDATED_AT"));
        term.draft();if(term.revision()<1)throw BusinessGlossary.invalid();return term;
    }
    public Page page(String owner,String filter,int offset){
        String pattern="%"+filter.toUpperCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var rows=jdbc.query("SELECT * FROM "+table(owner)+" WHERE UPPER(TERM_TEXT) LIKE ? ESCAPE '!' ORDER BY TERM_TEXT,TERM_ID OFFSET ? ROWS FETCH NEXT 21 ROWS ONLY",(r,n)->term(r),pattern,offset);
        return new Page(rows.stream().limit(20).toList(),rows.size()>20,offset);
    }
    public Term find(String owner,String id){var rows=jdbc.query("SELECT * FROM "+table(owner)+" WHERE TERM_ID=?",(r,n)->term(r),BusinessGlossary.id(id));if(rows.size()!=1)throw BusinessGlossary.stale();return rows.getFirst();}
    public Term save(String owner,String id,long revision,Draft d){
        String aliases=json.writeValueAsString(d.aliases()),search=d.term()+"\n"+String.join("\n",d.aliases())+"\n"+d.definition();
        boolean insert=id==null||id.isBlank();String termId=insert?UUID.randomUUID().toString():BusinessGlossary.id(id);
        if(insert&&revision!=0||!insert&&revision<1)throw BusinessGlossary.invalid();
        String sql=insert?"INSERT INTO "+table(owner)+" (TERM_TEXT,ALIASES_JSON,DEFINITION_TEXT,SQL_CRITERIA,ENABLED_YN,SEARCH_TEXT,TERM_ID,REVISION) VALUES (?,?,?,COALESCE(?,EMPTY_CLOB()),?,?,?,1)":
            "UPDATE "+table(owner)+" SET TERM_TEXT=?,ALIASES_JSON=?,DEFINITION_TEXT=?,SQL_CRITERIA=COALESCE(?,EMPTY_CLOB()),ENABLED_YN=?,SEARCH_TEXT=?,REVISION=REVISION+1,UPDATED_AT=SYSTIMESTAMP WHERE TERM_ID=? AND REVISION=?";
        int changed=jdbc.update(sql,s->{s.setString(1,d.term());s.setClob(2,new StringReader(aliases),aliases.length());s.setClob(3,new StringReader(d.definition()),d.definition().length());if(d.criteria().isEmpty())s.setNull(4,Types.CLOB);else s.setClob(4,new StringReader(d.criteria()),d.criteria().length());s.setString(5,d.enabled()?"Y":"N");s.setClob(6,new StringReader(search),search.length());s.setString(7,termId);if(!insert)s.setLong(8,revision);});
        if(changed!=1)throw BusinessGlossary.stale();return find(owner,termId);
    }
    public List<Key> keys(String owner){
        var rows=jdbc.query("SELECT TERM_ID,REVISION,TERM_TEXT,ALIASES_JSON FROM "+table(owner)+" WHERE ENABLED_YN='Y' ORDER BY TERM_TEXT,TERM_ID FETCH FIRST 2001 ROWS ONLY",(r,n)->new Key(r.getString(1),r.getLong(2),r.getString(3),aliases(r)));
        if(rows.size()>BusinessGlossary.MAX_TERMS)throw BusinessGlossary.failure(413,"활성 용어가 검색 한도 2,000개를 초과합니다. 일부만 분석하지 않았습니다.");return rows;
    }
    /** DB-native registered-phrase matching; Java only binds values and decodes the result. */
    public List<Target> phraseTargets(String owner,String question,String query){
        if(query.isBlank())return List.of();
        return jdbc.execute((ConnectionCallback<List<Target>>)c->{
            try(CallableStatement s=c.prepareCall(phraseSearch(owner))){
                s.setQueryTimeout(30);s.setString(1,question);s.setString(2,query);s.registerOutParameter(3,Types.CLOB);s.execute();
                Clob value=s.getClob(3);
                if(value==null)throw BusinessGlossary.invalid();
                try{
                    if(value.length()>400000)throw BusinessGlossary.failure(413,"일치한 용어 표현이 너무 많습니다. 질문 범위를 줄여 주세요.");
                    var rows=json.readTree(value.getSubString(1,(int)value.length()));
                    if(!rows.isArray()||rows.size()>512)throw BusinessGlossary.invalid();
                    var result=new ArrayList<Target>();
                    for(var item:rows){
                        String kind=item.path("kind").asString();var ids=item.path("termIds");
                        if(!List.of("TERM","ALIAS").contains(kind)||!ids.isArray()||ids.isEmpty())throw BusinessGlossary.invalid();
                        var termIds=new ArrayList<String>();for(var id:ids)termIds.add(BusinessGlossary.id(id.asString()));
                        result.add(new Target(BusinessGlossary.text(item.path("expression").asString(),256,true),kind,termIds));
                    }
                    return List.copyOf(result);
                }finally{value.free();}
            }
        });
    }
    public List<Token> tokens(String question){
        return jdbc.execute((ConnectionCallback<List<Token>>)c->{
            String sql="""
                DECLARE t CTX_DOC.TOKEN_TAB; a JSON_ARRAY_T:=JSON_ARRAY_T(); o JSON_OBJECT_T; n BINARY_INTEGER;
                BEGIN
                  CTX_DOC.POLICY_TOKENS(policy_name=>'DBC_BT_KO_POLICY',document=>?,restab=>t,format=>'TEXT');
                  IF t.COUNT>256 THEN RAISE_APPLICATION_ERROR(-20001,'Token limit'); END IF;
                  n:=t.FIRST;
                  WHILE n IS NOT NULL LOOP
                    o:=JSON_OBJECT_T();o.put('token',t(n).token);o.put('offset',t(n).offset);o.put('length',t(n).length);a.append(o);n:=t.NEXT(n);
                  END LOOP;
                  ?:=a.to_clob();
                END;
                """;
            try(CallableStatement s=c.prepareCall(sql)){
                s.setQueryTimeout(30);s.setString(1,question);s.registerOutParameter(2,Types.CLOB);s.execute();Clob value=s.getClob(2);
                try{if(value.length()>100000)throw BusinessGlossary.failure(413,"형태소 분석 결과가 너무 큽니다.");var rows=json.readTree(value.getSubString(1,(int)value.length()));var result=new ArrayList<Token>();for(var item:rows)result.add(new Token(item.path("token").asString(),item.path("offset").asInt(),item.path("length").asInt()));return List.copyOf(result);}finally{value.free();}
            }
        });
    }
}
