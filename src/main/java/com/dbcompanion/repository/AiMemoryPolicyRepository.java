package com.dbcompanion.repository;

import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.model.AiMemoryPolicy;
import com.dbcompanion.model.AiMemoryPolicy.*;
import com.dbcompanion.model.SemanticContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AiMemoryPolicyRepository {
    private final JdbcTemplate jdbc;
    private static final JsonMapper JSON = new JsonMapper();
    public AiMemoryPolicyRepository(JdbcTemplate template) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));
        jdbc.setQueryTimeout(15);
    }
    private static String object(String owner, String name) { return ProfileHistorySql.object(owner, name); }
    public Capability capability() {
        String version = "";
        try {
            version = jdbc.queryForObject("SELECT VERSION_FULL FROM PRODUCT_COMPONENT_VERSION WHERE PRODUCT LIKE 'Oracle%Database%' FETCH FIRST 1 ROW ONLY", String.class);
            if (!SemanticContext.versionSupported(version)) return new Capability(version, "", false, false, "", "검증 기준 버전 미만입니다.");
            var owners = jdbc.queryForList("SELECT TABLE_OWNER FROM SYS.ALL_SYNONYMS WHERE OWNER='PUBLIC' AND SYNONYM_NAME='DBMS_CLOUD_AI' AND TABLE_NAME='DBMS_CLOUD_AI' AND DB_LINK IS NULL", String.class);
            if (owners.size() != 1) return new Capability(version, "", false, false, "", "DBMS_CLOUD_AI 접근을 확인할 수 없습니다.");
            String owner = owners.getFirst();
            boolean memory = memoryCreateSignature(arguments(owner, "CREATE_MEMORY_STORE"))
                    && columns(owner, "USER_AI_MEMORY_STORES", List.of("MEMORY_STORE_ID", "MEMORY_STORE_NAME", "PROFILE_NAME", "DESCRIPTION"))
                    && columns(owner, "USER_AI_MEMORY_STORE_ATTRIBUTES", List.of("MEMORY_STORE_NAME", "ATTRIBUTE_NAME", "ATTRIBUTE_VALUE"));
            String guardView = "";
            if (columns(owner, "USER_AI_GUARDRAILS", List.of("GUARDRAIL_NAME", "STATUS", "DESCRIPTION"))
                    && columns(owner, "USER_AI_GUARDRAIL_ATTRIBUTES", List.of("GUARDRAIL_NAME", "ATTRIBUTE_NAME", "ATTRIBUTE_VALUE")))
                guardView = "USER_AI_GUARDRAILS";
            boolean guard = !guardView.isEmpty() && signature(owner, "CREATE_GUARDRAIL", List.of("GUARDRAIL_NAME:VARCHAR2:IN", "ATTRIBUTES:CLOB:IN", "STATUS:VARCHAR2:IN", "DESCRIPTION:CLOB:IN"));
            return new Capability(version, owner, memory, guard, guardView, "API 인자와 조회 컬럼 기준으로 기능별 지원을 확인합니다.");
        } catch (org.springframework.dao.DataAccessException ex) {
            return new Capability(version, "", false, false, "", "지원 여부를 조회하지 못했습니다. DB 연결과 권한을 확인하세요.");
        }
    }
    private boolean signature(String owner, String api, List<String> expected) {
        return arguments(owner, api).equals(expected);
    }
    private List<String> arguments(String owner, String api) {
        return jdbc.queryForList("SELECT ARGUMENT_NAME||':'||DATA_TYPE||':'||IN_OUT FROM SYS.ALL_ARGUMENTS WHERE OWNER=? AND PACKAGE_NAME='DBMS_CLOUD_AI' AND OBJECT_NAME=? ORDER BY SUBPROGRAM_ID,SEQUENCE", String.class, owner, api);
    }
    public static boolean memoryCreateSignature(List<String> arguments) {
        return arguments.equals(List.of("MEMORY_STORE_NAME:VARCHAR2:IN", "PROFILE_NAME:VARCHAR2:IN", "DESCRIPTION:CLOB:IN", "ATTRIBUTES:CLOB:IN"))
                || arguments.equals(List.of("MEMORY_STORE_NAME:VARCHAR2:IN", "MEMORY_PROFILE_NAME:VARCHAR2:IN", "DESCRIPTION:CLOB:IN", "ATTRIBUTES:CLOB:IN"));
    }
    private boolean columns(String owner, String view, List<String> required) {
        var columns = jdbc.queryForList("SELECT COLUMN_NAME FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=?", String.class, owner, view);
        if (!columns.containsAll(required)) return false;
        try { jdbc.queryForList("SELECT " + String.join(",", required) + " FROM " + object(owner, view) + " WHERE 1=0"); return true; }
        catch (org.springframework.dao.DataAccessException ex) { return false; }
    }
    public Capability require(String kind) {
        var result = capability();
        if (!(kind.equals("memory") ? result.memory() : result.guardrail()))
            throw AiMemoryPolicy.error(409, "대상 DB에서 이 기능의 API·조회 지원을 확인하지 못했습니다.");
        return result;
    }
    public List<Resource> stores(Capability c) {
        if (!c.memory()) return List.of();
        return jdbc.query("SELECT MEMORY_STORE_NAME,PROFILE_NAME,DESCRIPTION FROM " + object(c.owner(), "USER_AI_MEMORY_STORES")
                + " ORDER BY MEMORY_STORE_NAME FETCH FIRST 1000 ROWS ONLY",
                (r, i) -> new Resource(r.getString(1), r.getString(2), "", r.getString(3)));
    }
    public List<Resource> guardrails(Capability c) {
        if (!c.guardrail()) return List.of();
        return jdbc.query("SELECT GUARDRAIL_NAME,STATUS,DESCRIPTION FROM " + object(c.owner(), c.guardrailView())
                + " ORDER BY GUARDRAIL_NAME FETCH FIRST 1000 ROWS ONLY", (r, i) -> new Resource(r.getString(1), "", r.getString(2), r.getString(3)));
    }
    public List<String> profiles() {
        return jdbc.queryForList("SELECT PROFILE_NAME FROM USER_CLOUD_AI_PROFILES WHERE UPPER(STATUS)='ENABLED' ORDER BY PROFILE_NAME FETCH FIRST 1000 ROWS ONLY", String.class);
    }
    public StoreDetails storeDetails(String name) {
        AiMemoryPolicy.name(name);
        var c = require("memory");
        var rows = jdbc.query("SELECT MEMORY_STORE_ID,MEMORY_STORE_NAME,PROFILE_NAME,DESCRIPTION FROM "
                + object(c.owner(), "USER_AI_MEMORY_STORES") + " WHERE MEMORY_STORE_NAME=?",
                (r, i) -> new StoreDetails(r.getString(1), r.getString(2), r.getString(3), r.getString(4), Map.of()), name);
        if (rows.size() != 1) throw AiMemoryPolicy.error(404, "현재 소유자의 저장소를 찾을 수 없습니다.");
        Map<String,String> attributes = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT ATTRIBUTE_NAME,ATTRIBUTE_VALUE FROM " + object(c.owner(), "USER_AI_MEMORY_STORE_ATTRIBUTES")
                + " WHERE MEMORY_STORE_NAME=? ORDER BY ATTRIBUTE_NAME", (org.springframework.jdbc.core.RowCallbackHandler)
                r -> attributes.put(r.getString(1), r.getString(2)), name);
        var row = rows.getFirst();
        return new StoreDetails(row.id(), row.name(), row.profile(), row.description(), attributes);
    }
    public List<Conversation> conversations(String profile) {
        AiMemoryPolicy.name(profile);
        return jdbc.query("SELECT c.CONVERSATION_ID,c.CONVERSATION_TITLE,TO_CHAR(c.MODIFIED,'YYYY-MM-DD HH24:MI:SS TZH:TZM'),COUNT(*)"
                + " FROM USER_CLOUD_AI_CONVERSATIONS c JOIN USER_CLOUD_AI_CONVERSATION_PROMPTS p ON p.CONVERSATION_ID=c.CONVERSATION_ID"
                + " WHERE p.PROFILE_NAME=? GROUP BY c.CONVERSATION_ID,c.CONVERSATION_TITLE,c.MODIFIED"
                + " ORDER BY c.MODIFIED DESC,c.CONVERSATION_ID DESC FETCH FIRST 50 ROWS ONLY",
                (r, i) -> new Conversation(r.getString(1), r.getString(2), r.getString(3), r.getLong(4)), profile);
    }
    public void validateExtraction(Extract input) {
        AiMemoryPolicy.validateExtraction(input);
        var c = require("memory");
        if (!signature(c.owner(), "EXTRACT_MEMORIES", List.of("MEMORY_STORE_NAME:VARCHAR2:IN", "MEMORY_STORE_ID:VARCHAR2:IN", "CONVERSATION_ID:VARCHAR2:IN", "RANGE_START_TIME:TIMESTAMP WITH TIME ZONE:IN", "RANGE_END_TIME:TIMESTAMP WITH TIME ZONE:IN")))
            throw AiMemoryPolicy.error(409, "이 DB의 메모리 추출 인자 규격을 확인하지 못했습니다.");
        if (!input.store().equals(settings(input.profile()).get("memory_store_name")))
            throw AiMemoryPolicy.error(409, "프로필에 연결된 저장소가 변경되었습니다. 새로고침하세요.");
        storeDetails(input.store());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM USER_CLOUD_AI_CONVERSATION_PROMPTS WHERE CONVERSATION_ID=? AND PROFILE_NAME=?", Integer.class, input.conversationId(), input.profile());
        Integer other = jdbc.queryForObject("SELECT COUNT(*) FROM USER_CLOUD_AI_CONVERSATION_PROMPTS WHERE CONVERSATION_ID=? AND (PROFILE_NAME IS NULL OR PROFILE_NAME<>?)", Integer.class, input.conversationId(), input.profile());
        if (count == null || count == 0 || other == null || other != 0)
            throw AiMemoryPolicy.error(409, "이 프로필만 사용한 현재 소유자의 대화를 선택하세요. 여러 프로필이 섞인 대화는 추출하지 않습니다.");
    }
    public void extract(Extract input) {
        var c = require("memory");
        jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.prepareCall("BEGIN " + object(c.owner(), "DBMS_CLOUD_AI")
                    + ".EXTRACT_MEMORIES(memory_store_name=>?,conversation_id=>?); END;")) {
                statement.setQueryTimeout(120); statement.setString(1, input.store()); statement.setString(2, input.conversationId()); statement.execute();
            }
            return null;
        });
    }
    public Map<String,String> settings(String profile) {
        var values = new java.util.HashMap<String,String>();
        jdbc.query("SELECT ATTRIBUTE_NAME,ATTRIBUTE_VALUE FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES WHERE PROFILE_NAME=? AND ATTRIBUTE_NAME IN ('provider','credential_name','region','oci_compartment_id','embedding_model','memory_store_name','guardrail','conversation')",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> values.put(r.getString(1), r.getString(2)), profile);
        return values;
    }
    public List<Observation> observations(String profile) {
        var c = require("guardrail");
        String view = "USER_CLOUD_AI_CONVERSATION_PROMPTS";
        if (!columns(c.owner(), view, List.of("PROMPT_GUARDRAIL_STATUS", "RESPONSE_GUARDRAIL_STATUS")))
            throw AiMemoryPolicy.error(409, "이 DB에서는 Guardrail 검사 상태 조회가 지원되지 않습니다.");
        return jdbc.query("SELECT CONVERSATION_PROMPT_ID,CONVERSATION_ID,TO_CHAR(CREATED,'YYYY-MM-DD HH24:MI:SS TZH:TZM'),"
                + "PROMPT_GUARDRAIL_STATUS,RESPONSE_GUARDRAIL_STATUS,DBMS_LOB.SUBSTR(PROMPT,2000,1),DBMS_LOB.SUBSTR(PROMPT_RESPONSE,2000,1)"
                + " FROM " + object(c.owner(), view) + " WHERE PROFILE_NAME=? ORDER BY CREATED DESC,CONVERSATION_PROMPT_ID DESC FETCH FIRST 20 ROWS ONLY",
                (r, i) -> new Observation(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7)), profile);
    }
    public void requireLink(String attribute, String value) {
        if (!AiMemoryPolicy.LINKS.contains(attribute)) return;
        boolean memory = attribute.equals("memory_store_name");
        var c = require(memory ? "memory" : "guardrail");
        if ("".equals(value)) return;
        AiMemoryPolicy.name(value);
        String view = memory ? "USER_AI_MEMORY_STORES" : c.guardrailView();
        String column = memory ? "MEMORY_STORE_NAME" : "GUARDRAIL_NAME";
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + object(c.owner(), view) + " WHERE " + column + "=?"
                + (memory ? "" : " AND UPPER(STATUS)='ENABLED'"), Integer.class, value);
        if (count == null || count != 1) throw AiMemoryPolicy.error(409, "선택한 저장소·활성 정책이 없습니다. 목록을 새로고침하세요.");
    }
    public String attributes(Create input) {
        if (!profiles().contains(input.profile())) throw AiMemoryPolicy.error(409, "현재 소유자의 활성 프로필을 선택하세요.");
        var settings = settings(input.profile());
        var json = JSON.createObjectNode();
        if (input.kind().equals("memory")) {
            if (Objects.toString(settings.get("embedding_model"), "").isBlank())
                throw AiMemoryPolicy.error(409, "선택한 프로필에 embedding_model을 먼저 설정하세요.");
            json.put("match_limit", input.matchLimit());
            if (input.refreshRate() != null) json.put("refresh_rate", input.refreshRate());
        } else {
            if (!"oci".equals(settings.get("provider"))) throw AiMemoryPolicy.error(409, "OCI 연결 프로필을 선택하세요.");
            for (String key : List.of("provider", "credential_name", "region", "oci_compartment_id")) {
                String value = settings.get(key);
                if (value == null || value.isBlank()) throw AiMemoryPolicy.error(409, "OCI 연결 프로필의 필수 연결 설정을 확인하세요: " + key);
                json.put(key, value);
            }
            json.put("mode", input.mode()); json.put("oci_pii", input.pii());
            json.put("oci_prompt_injection", input.injection()); json.put("oci_content_moderation", input.moderation());
        }
        return json.toString();
    }
    public void create(Capability capability, Create input, String attributes) {
        if (input.kind().equals("memory")) {
            jdbc.update("BEGIN " + object(capability.owner(), "DBMS_CLOUD_AI")
                    + ".CREATE_MEMORY_STORE(?,?,?,?); END;", s -> {
                s.setString(1, input.name()); s.setString(2, input.profile());
                s.setClob(3, new java.io.StringReader(input.description())); s.setClob(4, new java.io.StringReader(attributes));
            });
        } else {
            jdbc.update("BEGIN " + object(capability.owner(), "DBMS_CLOUD_AI")
                    + ".CREATE_GUARDRAIL(guardrail_name=>?,attributes=>?,status=>'ENABLED',description=>?); END;", s -> {
                s.setString(1, input.name()); s.setClob(2, new java.io.StringReader(attributes));
                s.setClob(3, new java.io.StringReader(input.description()));
            });
        }
    }
    public boolean matches(Capability c, Create input, String attributes) {
        boolean memory = input.kind().equals("memory");
        var rows = (memory ? stores(c) : guardrails(c)).stream().filter(r -> r.name().equals(input.name())).toList();
        if (rows.size() != 1) return false;
        var row = rows.getFirst();
        if (!Objects.toString(row.description(), "").equals(input.description())
                || memory && !row.profile().equals(input.profile()) || !memory && !"ENABLED".equalsIgnoreCase(row.status())) return false;
        String view = memory ? "USER_AI_MEMORY_STORE_ATTRIBUTES" : "USER_AI_GUARDRAIL_ATTRIBUTES";
        String column = memory ? "MEMORY_STORE_NAME" : "GUARDRAIL_NAME";
        Map<String,String> actual = new java.util.HashMap<>();
        jdbc.query("SELECT ATTRIBUTE_NAME,ATTRIBUTE_VALUE FROM " + object(c.owner(), view) + " WHERE " + column + "=?",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> actual.put(r.getString(1), r.getString(2)), input.name());
        for (var entry : JSON.readTree(attributes).properties()) {
            // refresh_rate is a pipeline setting, not a store attribute. Until verified
            // against its pipeline, never report complete readback for a supplied value.
            if (entry.getKey().equals("refresh_rate")) return false;
            String value = actual.get(entry.getKey());
            if (value == null || !(entry.getValue().isString() ? entry.getValue().stringValue().equals(value)
                    : entry.getValue().equals(JSON.readTree(value)))) return false;
        }
        return true;
    }
}
