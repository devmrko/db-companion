package com.dbcompanion.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Application-held originals, distinct from the database's stored executable. */
public final class AgentDefinitionStore {
    private AgentDefinitionStore() {}
    private static Path file(String directory, String database, String owner, String name) throws Exception {
        String key = new JsonMapper().writeValueAsString(java.util.List.of(database, owner, name));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        return Path.of(directory).toAbsolutePath().resolve(digest + ".json");
    }
    public static JsonNode read(String directory, String database, String owner, String name) throws Exception {
        Path file = file(directory, database, owner, name);
        if (!Files.exists(file)) return null;
        if (Files.size(file) > 4_000_000) throw new IllegalStateException("Stored original exceeds limit");
        return new JsonMapper().readTree(Files.readString(file));
    }
    public static void save(String directory, String database, String owner, String name, JsonNode contract, JsonNode spec) throws Exception {
        Path file = file(directory, database, owner, name);
        Files.createDirectories(file.getParent());
        var value = new JsonMapper().createObjectNode();
        value.put("database", database); value.put("owner", owner); value.put("name", name);
        value.put("source", "application-archive"); value.put("savedAt", java.time.Instant.now().toString());
        value.set("contract", contract); value.set("spec", spec);
        Path temporary = Files.createTempFile(file.getParent(), "original-", ".tmp");
        try {
            Files.writeString(temporary, value.toString());
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
