package com.dbcompanion.repository;

import com.dbcompanion.model.AiMemoryPolicy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only adapter for the observed native plsql-001 store schema. */
@Repository
public class MemoryBrowseRepository {
    private final JdbcTemplate jdbc;
    private final AiMemoryPolicyRepository policies;
    public MemoryBrowseRepository(JdbcTemplate jdbc, AiMemoryPolicyRepository policies) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(15); this.policies = policies;
    }
    public record Row(String kind, String id, String threadId, String userId, String agentId,
                      String spaceId, String type, String status, String created, String content) {}
    public record Page(String storeId, List<Row> rows, int page, boolean hasNext, String method) {}
    public static final List<String> KINDS = List.of("memory", "message", "summary", "actor", "conversation");
    public static void validate(String kind, String query, int page) {
        if (!("all".equals(kind) || KINDS.contains(kind)) || query == null || query.length() > 500
                || query.indexOf('\0') >= 0 || page < 0 || page > 100000)
            throw AiMemoryPolicy.error(400, "유형·검색어(500자 이내)·페이지를 확인하세요.");
    }
    private String verify(String name) {
        AiMemoryPolicy.name(name);
        var store = policies.storeDetails(name);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM USER_AGENT_MEMORY_STORES WHERE MEMORY_STORE_NAME=? AND RAWTOHEX(MEMORY_STORE_UID)=? AND SCHEMA_VERSION='plsql-001'", Integer.class, name, store.id());
        if (count == null || count != 1 || name.length() > 100)
            throw AiMemoryPolicy.error(409, "이 저장소의 조회 구조를 확인하지 못했습니다. 지원 여부를 확인해야 합니다.");
        return store.id();
    }
    private String branch(String store, String kind, boolean detail) {
        if (kind.equals("conversation")) {
            String content = "(p.PROMPT || CHR(10) || p.PROMPT_RESPONSE)";
            return "SELECT 'conversation' KIND,CAST(p.CONVERSATION_PROMPT_ID AS VARCHAR2(256)) ID,p.CONVERSATION_ID THREAD_ID,"
                    + "CAST(NULL AS VARCHAR2(128)) USER_ID,CAST(NULL AS VARCHAR2(128)) AGENT_ID,CAST(NULL AS VARCHAR2(128)) SPACE_ID,"
                    + "p.PROFILE_NAME RECORD_TYPE,'stored' STATUS,TO_CHAR(p.CREATED,'YYYY-MM-DD HH24:MI:SS TZH:TZM') CREATED,"
                    + (detail ? content : "DBMS_LOB.SUBSTR(" + content + ",1000,1)")
                    + " CONTENT FROM USER_CLOUD_AI_CONVERSATION_PROMPTS p WHERE EXISTS (SELECT 1 FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES a"
                    + " WHERE a.PROFILE_NAME=p.PROFILE_NAME AND a.ATTRIBUTE_NAME='memory_store_name' AND DBMS_LOB.SUBSTR(a.ATTRIBUTE_VALUE,125,1)='" + store + "')"
                    + (detail ? " AND CAST(p.CONVERSATION_PROMPT_ID AS VARCHAR2(256))=?" : " AND (? IS NULL OR DBMS_LOB.INSTR(LOWER(" + content + "),LOWER(?))>0 OR CAST(p.CONVERSATION_PROMPT_ID AS VARCHAR2(256))=?)");
        }
        String suffix = switch(kind) { case "memory" -> "MEMORY"; case "message" -> "MESSAGE";
            case "summary" -> "THREAD_SUMMARY"; case "actor" -> "ACTOR_PROFILE"; default -> throw AiMemoryPolicy.error(400,"잘못된 유형입니다."); };
        boolean actor = kind.equals("actor");
        String content = actor ? "INFORMATION" : "CONTENT";
        String id = actor ? "ACTOR_ID" : "RECORD_ID";
        String type = switch(kind) { case "memory" -> "MEMORY_TYPE"; case "message" -> "MESSAGE_ROLE"; case "actor" -> "ACTOR_TYPE"; default -> "'summary'"; };
        return "SELECT '" + kind + "' KIND," + id + " ID,"
                + (actor ? "CAST(NULL AS VARCHAR2(128)) THREAD_ID,CASE WHEN ACTOR_TYPE='user' THEN ACTOR_ID END USER_ID,CASE WHEN ACTOR_TYPE='agent' THEN ACTOR_ID END AGENT_ID," : "THREAD_ID,USER_ID,AGENT_ID,")
                + "SPACE_ID," + type + " RECORD_TYPE,STATUS,TO_CHAR(CREATED_AT,'YYYY-MM-DD HH24:MI:SS TZH:TZM') CREATED,"
                + (detail ? content : "DBMS_LOB.SUBSTR(" + content + ",1000,1)") + " CONTENT FROM \"" + store + "_" + suffix + "\""
                + (detail ? " WHERE " + id + "=?" : " WHERE (? IS NULL OR DBMS_LOB.INSTR(LOWER(" + content + "),LOWER(?))>0 OR " + id + "=?)");
    }
    public Page browse(String name, String kind, String query, int page) {
        validate(kind, query, page); String storeId = verify(name);
        List<String> branches = new ArrayList<>(); List<Object> args = new ArrayList<>();
        for (String value : "all".equals(kind) ? KINDS : List.of(kind)) {
            branches.add(branch(name, value, false)); args.add(query); args.add(query); args.add(query);
        }
        args.add(page * 25); args.add(26);
        var rows = jdbc.query("SELECT * FROM (" + String.join(" UNION ALL ", branches)
                + ") ORDER BY CREATED DESC,KIND,ID OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", (r,i) -> row(r), args.toArray());
        return new Page(storeId, rows.stream().limit(25).toList(), page, rows.size() > 25,
                "내용 부분 문자열·레코드 ID 검색 · 모델 호출 없음");
    }
    public Row detail(String name, String kind, String id) {
        validate(kind, "", 0); if (!KINDS.contains(kind) || id == null || id.length() > 256) throw AiMemoryPolicy.error(400,"레코드를 선택하세요.");
        verify(name);
        var rows = jdbc.query(branch(name, kind, true), (r,i) -> row(r), id);
        if (rows.size() != 1) throw AiMemoryPolicy.error(404,"해당 레코드를 찾을 수 없습니다.");
        return rows.getFirst();
    }
    private Row row(java.sql.ResultSet r) throws java.sql.SQLException {
        String content;
        try (var reader = r.getCharacterStream("CONTENT")) {
            if (reader == null) content = "";
            else { char[] buffer = new char[200001]; int length = 0, read;
                while (length < buffer.length && (read = reader.read(buffer, length, buffer.length-length)) > 0) length += read;
                content = new String(buffer,0,Math.min(length,200000)) + (length > 200000 ? "\n[200,000자 표시 한도 초과]" : ""); }
        } catch (java.io.IOException ex) { throw new java.sql.SQLException("Memory content read failed", ex); }
        return new Row(r.getString("KIND"),r.getString("ID"),r.getString("THREAD_ID"),r.getString("USER_ID"),r.getString("AGENT_ID"),r.getString("SPACE_ID"),r.getString("RECORD_TYPE"),r.getString("STATUS"),r.getString("CREATED"),content);
    }
}
