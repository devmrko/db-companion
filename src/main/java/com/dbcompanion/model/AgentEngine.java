package com.dbcompanion.model;

import com.dbcompanion.common.exception.MetadataEditException;
import tools.jackson.databind.json.JsonMapper;

/** Execution payloads are bound as CLOBs, never interpolated into SQL. */
public final class AgentEngine {
    private AgentEngine() {}
    public record Run(String schema, String spec, String inputs, boolean confirmed) {}
    public record Result(String output, String trace) {}
    public record StoredRun(String schema, String name, String inputs, String contract, boolean confirmed) {}
    public static void name(String name) {
        if (name == null || name.isBlank() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl))
            throw error(400, "등록된 Agent 이름을 선택하세요.");
    }
    public static tools.jackson.databind.JsonNode description(String raw) {
        try {
            var json = new JsonMapper();
            var node = json.readTree(raw);
            if (!node.isObject() || !node.path("name").isString()) throw error(502, "DB의 Agent 상세 형식을 확인하지 못했습니다.");
            var object = (tools.jackson.databind.node.ObjectNode) node;
            for (String key : java.util.List.of("inputs", "outputs")) {
                var field = node.path(key);
                if (field.isString()) field = json.readTree(field.asText());
                if (field == null || !field.isArray()) throw error(502, "DB의 입출력 규격을 확인하지 못했습니다.");
                object.set(key, field);
            }
            return object;
        } catch (MetadataEditException ex) { throw ex; }
        catch (RuntimeException ex) { throw error(502, "DB의 Agent 상세 JSON을 확인하지 못했습니다."); }
    }
    public static void validate(Run input) {
        if (input == null || !input.confirmed()) throw error(400, "도구 실행 범위를 확인하고 실행에 동의하세요.");
        if (input.schema() == null || input.schema().isBlank()) throw error(400, "스키마를 선택하세요.");
        if (input.spec() == null || input.spec().length() > 500_000 || input.inputs() == null || input.inputs().length() > 100_000)
            throw error(400, "AgentSpec은 500,000자, 입력은 100,000자까지 가능합니다.");
        try {
            var json = new JsonMapper();
            var spec = json.readTree(input.spec());
            var values = json.readTree(input.inputs());
            if (spec == null || !spec.isObject() || !"Flow".equals(spec.path("component_type").asText())
                    || !spec.path("inputs").isArray() || spec.path("agentspec_version").asText().isBlank()
                    || values == null || !values.isArray()) throw error(400, "Flow AgentSpec과 이름·값 배열 형식의 입력이 필요합니다.");
            var names = new java.util.HashSet<String>();
            for (var value : values) {
                String name = value.path("name").asText();
                if (name.isBlank() || !value.has("value") || !names.add(name))
                    throw error(400, "입력은 중복 없는 name과 value를 포함해야 합니다.");
            }
            var expected = new java.util.HashSet<String>();
            for (var field : spec.path("inputs")) expected.add(field.path("title").asText());
            if (!names.equals(expected)) throw error(400, "AgentSpec에 정의된 입력 이름과 실행 입력이 일치하지 않습니다.");
        } catch (MetadataEditException ex) { throw ex; }
        catch (RuntimeException ex) { throw error(400, "JSON 형식을 확인하세요."); }
    }
    public static MetadataEditException error(int status, String message) {
        return new MetadataEditException(status, "Agent Engine request rejected", message);
    }
}
