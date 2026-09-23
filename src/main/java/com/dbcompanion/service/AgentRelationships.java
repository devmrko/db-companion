package com.dbcompanion.service;

import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.AgentCatalog.Assignment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.common.exception.AppException.Code.AGENT_RELATION_INVALID;

/** Parses declared configuration, never inferred execution history. */
public final class AgentRelationships {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private AgentRelationships() {}

    public static List<Assignment> assignments(String value) {
        if (value == null || value.isBlank()) throw new AppException(AGENT_RELATION_INVALID);
        var root = array(value);
        var result = new ArrayList<Assignment>();
        for (var node : root) {
            if (!node.isObject()) throw new AppException(AGENT_RELATION_INVALID);
            result.add(new Assignment(name(node.get("name")), name(node.get("task"))));
        }
        return List.copyOf(result);
    }

    public static List<String> tools(String value) {
        if (value == null) return List.of();
        var result = new ArrayList<String>();
        for (var node : array(value)) result.add(name(node));
        return result.stream().distinct().toList();
    }

    public static String optionalName(String value) {
        if (value == null || value.isBlank()) return null;
        // Scalar attribute views usually return plain strings; accept JSON strings too.
        if (value.startsWith("\"")) {
            try { return identifier(JSON.readTree(value).stringValue()); }
            catch (RuntimeException ex) { throw new AppException(AGENT_RELATION_INVALID); }
        }
        return identifier(value);
    }

    private static JsonNode array(String value) {
        try {
            var root = JSON.readTree(value);
            if (root == null || !root.isArray()) throw new AppException(AGENT_RELATION_INVALID);
            return root;
        } catch (RuntimeException ex) { throw new AppException(AGENT_RELATION_INVALID); }
    }

    private static String name(JsonNode value) {
        if (value == null || !value.isString()) throw new AppException(AGENT_RELATION_INVALID);
        return identifier(value.stringValue());
    }

    private static String identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 260) throw new AppException(AGENT_RELATION_INVALID);
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 2) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
