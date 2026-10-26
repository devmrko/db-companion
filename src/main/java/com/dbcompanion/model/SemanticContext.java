package com.dbcompanion.model;

import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.common.i18n.UiMessages;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Native semantic context configuration; it does not execute an Agent Tool. */
public final class SemanticContext {
    private SemanticContext() {}
    public static final String VERIFIED_VERSION = "23.26.4.1.0";
    private static final JsonMapper JSON = JsonMapper.builder().enable(
            tools.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record Capability(String version, boolean supported, String reason, String owner) {}
    public record Source(String name, String type, String status, String description, java.util.Map<String,String> callbacks) {
        public Source(String name, String type, String status, String description) {
            this(name,type,status,description,java.util.Map.of());
        }
    }
    public record Create(String schema, String name, String type, String attributes, String description, boolean confirmed) {}
    public record Result(boolean verified, String message) {}
    public static MetadataEditException error(int status, String key) {
        return new MetadataEditException(status, key, UiMessages.text("semantic." + key, key));
    }
    /** Conservative tested baseline, not a claim about Oracle's earliest supported release. */
    public static boolean versionSupported(String version) {
        if (version == null || !version.matches("[0-9]+(?:\\.[0-9]+){4}")) return false;
        var actual = version.split("\\."); var minimum = VERIFIED_VERSION.split("\\.");
        try {
            for (int i = 0; i < minimum.length; i++) {
                int comparison = Integer.compare(Integer.parseInt(actual[i]), Integer.parseInt(minimum[i]));
                if (comparison != 0) return comparison > 0;
            }
            return true;
        } catch (NumberFormatException ex) { return false; }
    }
    public static JsonNode sources(String value) {
        JsonNode node = parse(value);
        if (!node.isArray() || node.size() > 100) throw error(400, "invalidSources");
        var names = new HashSet<String>();
        for (var item : node) {
            if (!item.isString() || !item.stringValue().matches("[A-Z][A-Z0-9_$#]{0,124}")
                    || !names.add(item.stringValue())) throw error(400, "invalidSources");
        }
        return node;
    }
    private static JsonNode parse(String value) {
        if (value == null || value.length() > 32767) throw error(400, "invalid");
        try { var node = JSON.readTree(value); if (node != null) return node; }
        catch (RuntimeException ex) { throw error(400, "invalid"); }
        throw error(400, "invalid");
    }
    public static JsonNode validate(Create input) {
        if (input == null || !input.confirmed()) throw error(400, "confirm");
        AiCreation.name(input.name()); AiCreation.text(input.description(), 4000);
        AiCreation.text(input.attributes(), 32767);
        var node = parse(input.attributes());
        if (!node.isObject() || !Set.of("custom", "mcp").contains(java.util.Objects.toString(input.type(), "")))
            throw error(400, "invalid");
        var scope = node.path("scope").asString();
        if (!Set.of("global", "object", "all").contains(scope)) throw error(400, "invalid");
        var allowed = input.type().equals("custom")
                ? Set.of("scope", "object_callback_func", "global_callback_func", "params")
                : Set.of("scope", "source_endpoint", "credential_name", "global_tool", "object_tool");
        if (node.properties().stream().anyMatch(e -> !allowed.contains(e.getKey()))) throw error(400, "invalid");
        if (input.type().equals("custom")) {
            for (String part : Set.of("object", "global")) {
                String key = part + "_callback_func";
                if (scope.equals(part) || scope.equals("all") || node.has(key)) {
                    String name = node.path(key).asString();
                    if (!node.path(key).isString() || !name.matches("[A-Za-z][A-Za-z0-9_$#]*(?:\\.[A-Za-z][A-Za-z0-9_$#]*){0,2}")
                            || name.length() > 386) throw error(400, "invalid");
                }
            }
            if (node.has("params") && !node.get("params").isObject()) throw error(400, "invalid");
        } else {
            required(node, "credential_name"); required(node, "source_endpoint");
            try {
                var uri = java.net.URI.create(node.get("source_endpoint").stringValue());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                        || uri.getRawFragment() != null || uri.getRawQuery() != null) throw error(400, "invalid");
            } catch (IllegalArgumentException ex) { throw error(400, "invalid"); }
            for (String part : Set.of("object", "global")) {
                if (!scope.equals(part) && !scope.equals("all") && !node.has(part + "_tool")) continue;
                var tool = node.path(part + "_tool"); required(tool, "name");
                if (!tool.path("arguments").isObject() || !tool.path("response_mapping").isObject()) throw error(400, "invalid");
                var mapping = tool.get("response_mapping");
                for (String key : Set.of("items", "term", "definition")) required(mapping, key);
                if (part.equals("object")) {
                    required(mapping, "object"); required(mapping, "object_pattern");
                    String pattern = mapping.get("object_pattern").stringValue();
                    if (!pattern.contains("${owner}") || !pattern.contains("${name}")) throw error(400, "invalid");
                }
            }
        }
        noSecrets(node);
        return node;
    }
    private static void required(JsonNode node, String key) {
        if (!node.path(key).isString() || node.path(key).asString().isBlank()) throw error(400, "invalid");
    }
    private static void noSecrets(JsonNode node) {
        if (node.isObject()) for (var entry : node.properties()) {
            if (!com.dbcompanion.common.db.ProfileEditPolicy.editableAttribute(entry.getKey().toLowerCase(java.util.Locale.ROOT)))
                throw error(400, "secret");
            noSecrets(entry.getValue());
        }
        else if (node.isArray()) for (var child : node) noSecrets(child);
    }
}
