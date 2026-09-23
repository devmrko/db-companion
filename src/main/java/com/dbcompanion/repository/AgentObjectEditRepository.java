package com.dbcompanion.repository;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AgentCatalog.*;
import com.dbcompanion.model.AgentObjectEdit.*;
import com.dbcompanion.service.SqlObjectName;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.common.db.AgentObjectEditPolicy.*;

@Repository
public class AgentObjectEditRepository {
    private final JdbcTemplate jdbc;
    private final AgentCatalogRepository catalog;
    private final TeamEditRepository shared;
    private final ProfileEditRepository profiles;
    public AgentObjectEditRepository(JdbcTemplate jdbc,AgentCatalogRepository catalog,TeamEditRepository shared,ProfileEditRepository profiles) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);
        this.catalog=catalog;this.shared=shared;this.profiles=profiles;
    }
    public Component current(HistoryTarget target) {
        kind(target.kind());var scope=new Scope(target.schema(),true);
        var items=catalog.items(scope,target.kind(),List.of(target.name()));
        return new Component(target.name(),items.isEmpty()?null:items.getFirst(),catalog.attributes(scope,target.kind(),items));
    }
    public String packageOwner(String schema) { return shared.packageOwner(schema); }
    public List<String> choices(Kind kind) { return shared.choices(kind); }
    public List<String> profiles() { return jdbc.queryForList("SELECT PROFILE_NAME FROM USER_CLOUD_AI_PROFILES ORDER BY PROFILE_NAME",String.class); }
    public List<String> credentials() { return profiles.credentials(); }
    private void profile(String name) {
        if(jdbc.queryForList("SELECT PROFILE_NAME FROM USER_CLOUD_AI_PROFILES WHERE PROFILE_NAME=?",String.class,reference(name)).isEmpty())
            throw missing(UiMessages.text("ui.4a7849864c3b", "프로필"));
    }
    private MetadataEditException missing(String label) { return new MetadataEditException(409,"Object reference missing",label+UiMessages.text("ui.965e923dbcb3", "을 찾을 수 없습니다. 최신 목록을 다시 불러와 주세요.")); }
    public void validateReferences(Target target,Component current,String desired,JsonMapper json) {
        switch(target.attribute()) {
            case "profile_name" -> profile(desired);
            case "tools" -> {
                var names=names(desired,json);
                if(catalog.items(new Scope(target.schema(),true),Kind.TOOL,names).size()!=names.size())throw missing("Tool");
            }
            case "input" -> validateTaskInput(target,desired);
            case "function" -> {
                if(optional(current,"tool_type")!=null)throw invalid(UiMessages.text("ui.08c884c2ce7d", "내장 Tool의 함수 연결은 수정할 수 없습니다."));
                routine(target.schema(),desired);
            }
            case "tool_type","tool_params" -> {
                String type=target.attribute().equals("tool_type")?desired:optional(current,"tool_type");
                String params=target.attribute().equals("tool_params")?desired:optional(current,"tool_params");
                var config=validateToolParams(type,params,json);
                if(config.has("profile_name"))profile(config.get("profile_name").stringValue());
                if(config.has("credential_name")&&!profiles.credentialAvailable(reference(config.get("credential_name").stringValue())))throw missing(UiMessages.text("ui.ef9620d8b73c", "활성 Credential"));
            }
            default -> { }
        }
    }
    private void validateTaskInput(Target target,String desired) {
        validateTaskChain(target.name(),desired,next->{
            var task=current(new HistoryTarget(target.schema(),Kind.TASK,next));if(task.info()==null)throw missing(UiMessages.text("ui.e51269481050", "입력 Task"));
            return optional(task,"input");
        });
    }
    public static JsonNode validateToolParams(String type,String params,JsonMapper json) {
        if(type==null||!TOOL_TYPES.contains(type.toUpperCase(Locale.ROOT)))throw invalid(UiMessages.text("ui.7f41e19405cd", "Tool 유형을 확인해 주세요."));
        if(params==null)throw invalid(UiMessages.text("ui.fbc74b034ae8", "tool_params 설정이 필요합니다."));
        var config=tree(params,json);if(!config.isObject())throw invalid(UiMessages.text("ui.5b5cd5262e22", "tool_params는 JSON 객체여야 합니다."));
        var required=switch(type.toUpperCase(Locale.ROOT)) {
            case "SQL","RAG" -> List.of("profile_name");
            case "WEBSEARCH" -> List.of("credential_name");
            case "NOTIFICATION" -> {
                required(config,"notification_type");String notification=config.get("notification_type").stringValue();
                if(notification.equalsIgnoreCase("slack"))yield List.of("credential_name","channel");
                if(notification.equalsIgnoreCase("email"))yield List.of("credential_name","recipient","sender","smtp_host");
                throw invalid(UiMessages.text("ui.0b2960b39645", "notification_type은 slack 또는 email이어야 합니다."));
            }
            default -> throw invalid(UiMessages.text("ui.323e0256395e", "지원하지 않는 Tool 유형입니다."));
        };
        required.forEach(key->required(config,key));
        for(String key:List.of("profile_name","credential_name"))if(config.has(key)){required(config,key);reference(config.get(key).stringValue());}
        return config;
    }
    private static void required(JsonNode config,String key) {
        if(!config.has(key)||!config.get(key).isString()||config.get(key).stringValue().isBlank())throw invalid(key+UiMessages.text("ui.df3cd8336982", " 값을 입력해 주세요."));
    }
    private record Routine(String owner,String object,String member) {}
    private void routine(String schema,String value) {
        var p=SqlObjectName.parts(value);
        var targets=switch(p.size()) {
            case 1 -> List.of(new Routine(schema,p.get(0),null));
            case 2 -> List.of(new Routine(p.get(0),p.get(1),null),new Routine(schema,p.get(0),p.get(1)));
            default -> List.of(new Routine(p.get(0),p.get(1),p.get(2)));
        };
        int matches=0;
        for(var target:targets) {
            String filter=target.member()==null?"OBJECT_TYPE IN ('FUNCTION','PROCEDURE') AND PROCEDURE_NAME IS NULL":"OBJECT_TYPE='PACKAGE' AND PROCEDURE_NAME=?";
            Object[] args=target.member()==null?new Object[]{target.owner(),target.object()}:new Object[]{target.owner(),target.object(),target.member()};
            if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_PROCEDURES WHERE OWNER=? AND OBJECT_NAME=? AND "+filter,Long.class,args)>0)matches++;
        }
        if(matches==0)throw missing(UiMessages.text("ui.d16d2192e3b0", "함수·프로시저"));
        if(matches>1)throw invalid(UiMessages.text("ui.7aefbd6f86eb", "함수 이름이 두 객체로 해석됩니다. 소유자·패키지를 포함한 이름으로 입력해 주세요."));
    }
    public static String sql(String owner,Kind kind) {
        kind(kind);
        return "DECLARE v_name VARCHAR2(4000) := ?; v_attribute VARCHAR2(128) := ?; v_value VARCHAR2(32767) := ?; BEGIN "
                +ProfileHistorySql.object(owner,"DBMS_CLOUD_AI_AGENT")+".SET_ATTRIBUTE(object_name => v_name, object_type => '"+kind.name()
                +"', attribute_name => v_attribute, attribute_value => v_value); END;";
    }
    public void save(String owner,Target target,String value) {
        jdbc.update(sql(owner,target.kind()),statement->{statement.setString(1,apiName(target.name()));statement.setString(2,target.attribute());statement.setString(3,value);});
    }
    public static String apiName(String name) { return "\""+name.replace("\"","\"\"")+"\""; }
}
