package com.dbcompanion.model;

import com.dbcompanion.common.i18n.UiMessages;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Current registered feedback, not an append-only audit history. */
public final class AiFeedback {
    private AiFeedback() {}
    public static final int PAGE_SIZE = 10;
    public static final String TABLE_SUFFIX = "_FEEDBACK_VECINDEX$VECTAB";

    public record Query(String schema, String profile, String search, String type, int page) {
        public Query {
            schema = schema == null ? "" : schema;
            profile = profile == null ? "" : profile;
            search = search == null ? "" : search.strip();
            type = type == null ? "" : type;
            if (schema.getBytes(StandardCharsets.UTF_8).length > 128 || profile.getBytes(StandardCharsets.UTF_8).length > 128
                    || schema.indexOf('\0') >= 0 || profile.indexOf('\0') >= 0 || search.indexOf('\0') >= 0
                    || search.length() > 500 || !List.of("", "positive", "negative").contains(type)
                    || page < 1 || page > 1000 || (!profile.isEmpty() && schema.isEmpty()))
                throw new IllegalArgumentException("Invalid Feedback filter");
        }
        public int offset() { return (page - 1) * PAGE_SIZE; }
    }
    public static String tableName(String profile) {
        String result = profile + TABLE_SUFFIX;
        if (profile == null || profile.isBlank() || profile.indexOf('\0') >= 0
                || result.getBytes(StandardCharsets.UTF_8).length > 128)
            throw new IllegalArgumentException("Invalid Feedback profile name");
        return result;
    }
    public static String rowId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9+/]{18}"))
            throw new IllegalArgumentException("Invalid Feedback row identifier");
        return id;
    }
    public static String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "positive" -> UiMessages.text("ui.d7cd27be0534", "긍정");
            case "negative" -> UiMessages.text("ui.2c78911d2e9f", "부정");
            case "" -> "—";
            default -> type;
        };
    }
    public static String preview(String text) {
        if (text == null) return "";
        return text.codePointCount(0, text.length()) > 500
                ? text.substring(0, text.offsetByCodePoints(0, 500)) + "…" : text;
    }
    public record Item(String id, String question, String type, String feedback) {
        public String getTypeLabel() { return typeLabel(type); }
    }
    public record Page(List<Item> items, int number, boolean hasNext) {
        public static Page of(List<Item> rows, int number) {
            return new Page(List.copyOf(rows.subList(0, Math.min(PAGE_SIZE, rows.size()))), number, rows.size() > PAGE_SIZE);
        }
    }
    public record Detail(String question, String type, String response, String feedback,
                         String sqlId, String sqlText, String attributes) {
        public String getTypeLabel() { return typeLabel(type); }
    }
    public record Result(List<AiProfile> profiles, boolean missingTable, Page page, Detail detail) {}
}
