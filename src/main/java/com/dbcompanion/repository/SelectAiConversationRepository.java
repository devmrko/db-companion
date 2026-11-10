package com.dbcompanion.repository;

import com.dbcompanion.common.db.ProfileHistorySql;
import java.sql.Types;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit per-request conversations: no pooled session conversation state. */
@Repository
public class SelectAiConversationRepository {
    private final JdbcTemplate jdbc;
    public SelectAiConversationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Map<String,String> settings(String profile){
        Map<String,String> values=new LinkedHashMap<>();
        jdbc.query("SELECT ATTRIBUTE_NAME,ATTRIBUTE_VALUE FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES WHERE PROFILE_NAME=? AND ATTRIBUTE_NAME IN ('conversation','memory_store_name')",
                (org.springframework.jdbc.core.RowCallbackHandler) r->values.put(r.getString(1),r.getString(2)),profile);
        return values;
    }
    public String create(String owner){
        return jdbc.execute((ConnectionCallback<String>) connection->{
            try(var call=connection.prepareCall("BEGIN ? := "+ProfileHistorySql.object(owner,"DBMS_CLOUD_AI")+".CREATE_CONVERSATION(attributes=>'{\"title\":\"DB Companion Select AI\",\"retention_days\":7}'); END;")){
                call.setQueryTimeout(15);call.registerOutParameter(1,Types.VARCHAR);call.execute();return call.getString(1);
            }
        });
    }
    public void extract(String owner,String store,String conversation){
        jdbc.execute((ConnectionCallback<Void>) connection->{
            try(var call=connection.prepareCall("BEGIN "+ProfileHistorySql.object(owner,"DBMS_CLOUD_AI")+".EXTRACT_MEMORIES(memory_store_name=>?,conversation_id=>?); END;")){
                call.setQueryTimeout(120);call.setString(1,store);call.setString(2,conversation);call.execute();return null;
            }
        });
    }
    public boolean recorded(String profile,String conversation){
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM USER_CLOUD_AI_CONVERSATION_PROMPTS WHERE CONVERSATION_ID=? AND PROFILE_NAME=?",Integer.class,conversation,profile);
        return count!=null&&count>0;
    }
}
