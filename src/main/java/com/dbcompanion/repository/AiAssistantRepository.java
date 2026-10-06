package com.dbcompanion.repository;

import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.AiAssistant.*;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiAssistantRepository {
    private final JdbcTemplate jdbc;
    public String maxTokens(String profile){
        var values=jdbc.query("SELECT ATTRIBUTE_VALUE FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES WHERE PROFILE_NAME=? AND ATTRIBUTE_NAME='max_tokens'",(r,n)->r.getString(1),profile);
        return values.isEmpty()?null:values.getFirst();
    }
    public void saveMaxTokens(String owner,String profile,int value){
        AiAssistant.validateTokens(value);
        jdbc.update("BEGIN "+ProfileHistorySql.object(owner,"DBMS_CLOUD_AI")+".SET_ATTRIBUTE(profile_name => ?, attribute_name => 'max_tokens', attribute_value => ?); END;",profile,Integer.toString(value));
    }
    public AiAssistantRepository(JdbcTemplate jdbc){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);}
    public List<Choice> profiles(){
        return jdbc.query("""
                SELECT p.PROFILE_NAME, DBMS_LOB.SUBSTR(a.ATTRIBUTE_VALUE,4000,1), DBMS_LOB.SUBSTR(m.ATTRIBUTE_VALUE,4000,1)
                FROM USER_CLOUD_AI_PROFILES p JOIN USER_CLOUD_AI_PROFILE_ATTRIBUTES a
                  ON a.PROFILE_NAME=p.PROFILE_NAME AND a.ATTRIBUTE_NAME='provider'
                LEFT JOIN USER_CLOUD_AI_PROFILE_ATTRIBUTES m ON m.PROFILE_NAME=p.PROFILE_NAME AND m.ATTRIBUTE_NAME='model'
                WHERE p.STATUS='ENABLED' ORDER BY p.PROFILE_NAME
                """,(r,n)->new Choice(r.getString(1),r.getString(2),r.getString(3)));
    }
    public Profile profile(Selection selected){
        var rows=jdbc.query("SELECT PROFILE_ID, STATUS FROM USER_CLOUD_AI_PROFILES WHERE PROFILE_NAME=?",
                (r,n)->r.getString(1)+":"+r.getString(2),selected.name());
        if(rows.size()!=1||!rows.getFirst().endsWith(":ENABLED"))throw AiAssistant.stale();
        final MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
        hash(digest,rows.getFirst());
        var attributes=jdbc.query("SELECT ATTRIBUTE_NAME, ATTRIBUTE_VALUE FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES WHERE PROFILE_NAME=? ORDER BY ATTRIBUTE_NAME",
                (r,n)->Map.entry(r.getString(1),Objects.toString(r.getString(2),"")),selected.name());
        String provider=null,model=null;
        for(var attribute:attributes){
            hash(digest,attribute.getKey());hash(digest,attribute.getValue());
            if(attribute.getKey().equals("provider"))provider=attribute.getValue();
            if(attribute.getKey().equals("model"))model=attribute.getValue();
        }
        if(provider==null||provider.isBlank())throw AiAssistant.stale();
        return new Profile(selected,provider,model,HexFormat.of().formatHex(digest.digest()));
    }
    private static void hash(MessageDigest digest,String value){
        var bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
    }
    public static String generateSql(String owner){
        return generateSql(owner,com.dbcompanion.model.SelectAiTest.Action.CHAT);
    }
    public static String generateSql(String owner,com.dbcompanion.model.SelectAiTest.Action action){
        return generateSql(owner,action,false);
    }
    public static String generateSql(String owner,com.dbcompanion.model.SelectAiTest.Action action,boolean override){
        String verb=switch(Objects.requireNonNull(action)){case SQL->"showsql";case CHAT->"chat";case PROMPT->"showprompt";};
        String api=ProfileHistorySql.object(owner,"DBMS_CLOUD_AI");
        return "BEGIN IF "+api+".GET_CONVERSATION_ID IS NOT NULL THEN RAISE_APPLICATION_ERROR(-20051, 'Active conversation is not allowed'); END IF; "
                +"? := "+api+".GENERATE(prompt => ?, profile_name => ?, action => '"+verb+"', attributes => "+(override?"?":"'{\"conversation\":false}'")+"); END;";
    }
    public String explain(String owner,String profile,String prompt){
        return generate(owner,profile,prompt,com.dbcompanion.model.SelectAiTest.Action.CHAT);
    }
    public String explain(String owner,String profile,String prompt,int timeoutSeconds){
        return generate(owner,profile,prompt,com.dbcompanion.model.SelectAiTest.Action.CHAT,timeoutSeconds);
    }
    public String generate(String owner,String profile,String prompt,com.dbcompanion.model.SelectAiTest.Action action){
        return generate(owner,profile,prompt,action,90);
    }
    public String generate(String owner,String profile,String prompt,com.dbcompanion.model.SelectAiTest.Action action,int timeoutSeconds){
        com.dbcompanion.common.config.SelectAiExecutionSettings.validate(timeoutSeconds);
        Integer tokens=jdbc.getDataSource() instanceof com.dbcompanion.common.db.SessionDataSource session?session.assistantMaxTokens(profile):null;
        return jdbc.execute((ConnectionCallback<String>) connection->{
            try(var call=connection.prepareCall(generateSql(owner,action,tokens!=null));var reader=new StringReader(prompt)){
                call.setQueryTimeout(timeoutSeconds);call.registerOutParameter(1,java.sql.Types.CLOB);
                call.setCharacterStream(2,reader,prompt.length());call.setString(3,profile);
                if(tokens!=null)call.setString(4,"{\"conversation\":false,\"max_tokens\":"+tokens+"}");
                call.execute();
                var result=call.getClob(1);
                if(result==null)throw new Failure(502,"assistant.emptyResult","설명 응답이 비어 있습니다. 자동 재시도하지 않았습니다.");
                try{
                    if(result.length()>AiAssistant.MAX_RESULT)throw new Failure(502,"assistant.resultTooLong","응답이 표시 한도를 초과했습니다. 자동 재시도하지 않았습니다.");
                    String text=result.getSubString(1,(int)result.length());
                    if(text.isBlank())throw new Failure(502,"assistant.emptyResult","설명 응답이 비어 있습니다. 자동 재시도하지 않았습니다.");
                    return text;
                }finally{result.free();}
            }
        });
    }
}
