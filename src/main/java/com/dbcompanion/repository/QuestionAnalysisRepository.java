package com.dbcompanion.repository;

import com.dbcompanion.common.db.QuestionAnalysisSql;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.BusinessGlossary;
import com.dbcompanion.model.BusinessGlossary.Token;
import com.dbcompanion.model.QuestionLanguage;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Questions are tokenized in Oracle, independently of dictionary tables and CONTEXT indexes. */
@Repository
public class QuestionAnalysisRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public QuestionAnalysisRepository(JdbcTemplate jdbc,JsonMapper json){
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(30);this.json=json;
    }
    public String policy(String owner){
        return policy(owner,QuestionLanguage.KO);
    }
    public String policy(String owner,QuestionLanguage language){
        // Compatibility is policy-only: an existing dictionary index is neither read nor required.
        if(ready(owner,language.policy(),language.lexer()))return language.policy();
        return language==QuestionLanguage.KO&&ready(owner,"DBC_BT_KO_POLICY",language.lexer())?"DBC_BT_KO_POLICY":"";
    }
    public boolean hasPreference(QuestionLanguage language){
        return !jdbc.queryForList("SELECT PRE_NAME FROM CTXSYS.CTX_USER_PREFERENCES WHERE PRE_NAME=?",String.class,language.preference()).isEmpty();
    }
    private boolean ready(String owner,String name,String lexer){
        var names=jdbc.queryForList("SELECT IDX_NAME FROM CTXSYS.CTX_USER_INDEXES WHERE IDX_NAME=?",String.class,name);
        if(names.isEmpty())return false;
        var types=jdbc.queryForList("SELECT IXO_OBJECT FROM CTXSYS.CTX_USER_INDEX_OBJECTS WHERE IXO_INDEX_NAME=? AND IXO_CLASS='LEXER'",String.class,name);
        int physical=jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_INDEXES WHERE OWNER=? AND INDEX_NAME=?",Integer.class,owner,name);
        if(!names.equals(List.of(name))||!types.equals(List.of(lexer))||physical!=0)
            throw BusinessGlossary.failure(409,UiMessages.text("questionAnalysis.invalid","Question analysis policy needs inspection. No settings were changed."));
        return true;
    }
    public List<Token> tokens(String policy,String question){
        QuestionLanguage.forPolicy(policy);
        return jdbc.execute((ConnectionCallback<List<Token>>)connection->{
            try(CallableStatement statement=connection.prepareCall(QuestionAnalysisSql.tokens())){
                statement.setQueryTimeout(30);statement.setString(1,policy);statement.setString(2,question);statement.registerOutParameter(3,Types.CLOB);statement.execute();
                Clob value=statement.getClob(3);if(value==null)throw BusinessGlossary.invalid();
                try{
                    if(value.length()>100000)throw BusinessGlossary.failure(413,UiMessages.text("questionAnalysis.limit","Question analysis exceeds the token limit."));
                    var rows=json.readTree(value.getSubString(1,(int)value.length()));
                    if(!rows.isArray()||rows.size()>256)throw BusinessGlossary.invalid();
                    var result=new ArrayList<Token>();
                    for(var item:rows)result.add(new Token(item.path("token").asString(),item.path("offset").asInt(),item.path("length").asInt()));
                    BusinessGlossary.textQuery(result);return List.copyOf(result);
                }finally{value.free();}
            }
        });
    }
}
