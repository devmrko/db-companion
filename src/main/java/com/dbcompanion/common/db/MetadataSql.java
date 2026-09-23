package com.dbcompanion.common.db;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.MetadataEdit.Target;
import com.dbcompanion.model.MetadataEdit.Value;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Only builds the two supported metadata DDL forms; never accepts a SQL fragment. */
public final class MetadataSql {
    private MetadataSql() {}
    private static MetadataEditException invalid(String message) {
        return new MetadataEditException(400, "Invalid metadata input", message);
    }
    public static String identifier(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) throw invalid(UiMessages.text("ui.360329d94389", "객체 이름을 확인해 주세요."));
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    public static String newAnnotationName(String value) {
        if (value == null) throw invalid(UiMessages.text("ui.abe32d6cdd6a", "Annotation 이름을 입력해 주세요."));
        String result = value.trim();
        if (result.startsWith("\"")) {
            if (!result.matches("\"(?:[^\"]|\"\")+\"")) throw invalid(UiMessages.text("ui.ea4a56fd037b", "Annotation 이름의 큰따옴표를 확인해 주세요."));
            result = result.substring(1, result.length() - 1).replace("\"\"", "\"");
        } else {
            if (result.indexOf('"') >= 0) throw invalid(UiMessages.text("ui.d5af20d4621c", "큰따옴표를 쓰려면 이름 전체를 감싸 주세요."));
            result = result.toUpperCase(Locale.ROOT);
        }
        identifier(result);
        if (bytes(result) > 1024) throw invalid(UiMessages.text("ui.d73fca5c6fdb", "Annotation 이름은 UTF-8 기준 1024바이트 이내로 입력해 주세요."));
        return result;
    }
    public static String normalizedValue(String value) { return value == null || value.isEmpty() ? null : value; }
    public static int bytes(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }
    private static String literal(String value) {
        if (value == null) return "''";
        if (value.indexOf('\0') >= 0 || bytes(value) > 4000)
            throw invalid(UiMessages.text("ui.5355b09c1b23", "값은 NUL 문자 없이 UTF-8 기준 4000바이트 이내로 입력해 주세요."));
        return "'" + value.replace("'", "''") + "'";
    }
    private static String table(Target target) { return identifier(target.schema()) + "." + identifier(target.table()); }
    public static String comment(Target target, String value) {
        if (value == null || value.isBlank()) throw invalid(UiMessages.text("ui.9a21575edd1a", "코멘트를 입력해 주세요. 코멘트 삭제는 지원하지 않습니다."));
        return "COMMENT ON " + (target.column() == null ? "TABLE " : "COLUMN ") + table(target)
                + (target.column() == null ? "" : "." + identifier(target.column())) + " IS " + literal(value);
    }
    public static String annotation(Target target, String name, String value, boolean add) {
        String clause = (add ? "ADD " : "REPLACE ") + identifier(name)
                + (normalizedValue(value) == null ? "" : " " + literal(value));
        return "ALTER TABLE " + table(target) + (target.column() == null ? "" : " MODIFY " + identifier(target.column()))
                + " ANNOTATIONS (" + clause + ")";
    }
    public static String version(Target target, String kind, String name, Value value) {
        if ("comment".equals(kind)) name = null;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{target.schema(), target.table(), target.column(), kind, name,
                    Boolean.toString(value.present()), normalizedValue(value.value()), Boolean.toString(value.inherited())}) {
                byte[] bytes = part == null ? new byte[0] : part.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(part == null ? -1 : bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static void verifyVersion(Target target, String kind, String name, Value value, String expected) {
        if (!version(target, kind, name, value).equals(expected))
            throw new MetadataEditException(409, "Metadata changed since editor opened",
                    UiMessages.text("ui.bc62860db801", "다른 변경이 감지되었습니다. 입력 내용을 복사한 뒤 편집 창을 다시 열어 주세요."));
    }
}
