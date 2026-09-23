package com.dbcompanion.common.db;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** A strict parser for this probe's two statement shapes, not a PL/SQL interpreter.
 * Receives audit text only; it cannot recover missing text from the intended test input. */
public final class InstructionAuditParser {
    private InstructionAuditParser() {}
    private static final String CALL = "BEGIN /\\* DBC_INSTRUCTION_([A-Z_]+) \\*/ "
            + "\"[A-Za-z0-9_#$]+\"\\.\"DBMS_CLOUD_AI\"\\.SET_ATTRIBUTE\\(profile_name => '(DBC_AP_[0-9A-F]{16})', "
            + "attribute_name => 'additional_instructions', attribute_value => ";
    private static final Pattern LITERAL = Pattern.compile(CALL + "('(?:[^']|'')*')\\); END;", Pattern.DOTALL);
    private static final Pattern BOUND = Pattern.compile("DECLARE v_value (?:VARCHAR2\\(32767\\)|CLOB) := :1\\s*; "
            + CALL + "v_value\\); END;", Pattern.DOTALL);
    private static final Pattern BIND = Pattern.compile("\\s*#1\\(([0-9]{1,9})\\):(.*)", Pattern.DOTALL);
    public record Parsed(String caseId, String profile, String attribute, String value, String status) {}
    public static Parsed parse(String sql, String binds) {
        if (sql == null) return unavailable("SQL_TEXT 없음");
        // Observed Oracle audit SQL_TEXT ends in one NUL. HTML hides this character.
        // Remove only that terminator, never NUL inside SQL or any part of the bound value.
        if (sql.endsWith("\0")) sql = sql.substring(0, sql.length() - 1);
        var literal = LITERAL.matcher(sql);
        if (literal.matches()) {
            String quoted = literal.group(3);
            return new Parsed(literal.group(1), literal.group(2), "additional_instructions",
                    quoted.substring(1, quoted.length() - 1).replace("''", "'"), "본문 추출");
        }
        var bound = BOUND.matcher(sql);
        if (!bound.matches()) return unavailable("지원하지 않는 SQL 형식");
        if (binds == null) return missing(bound.group(1), bound.group(2), "SQL_BINDS 없음");
        var bind = BIND.matcher(binds);
        if (!bind.matches()) return missing(bound.group(1), bound.group(2), "지원하지 않는 바인드 형식");
        String value = bind.group(2);
        int reported = Integer.parseInt(bind.group(1));
        // A matching header length does NOT prove completeness: Oracle can report the
        // captured prefix's length. Fullness is checked separately against the input hash.
        if (reported != value.codePointCount(0, value.length()) && reported != value.getBytes(StandardCharsets.UTF_8).length)
            return missing(bound.group(1), bound.group(2), "바인드 길이 불일치: 표시 " + reported
                    + ", 본문 " + value.codePointCount(0, value.length()) + "자 / " + value.getBytes(StandardCharsets.UTF_8).length + " bytes");
        return new Parsed(bound.group(1), bound.group(2), "additional_instructions", value, "본문 추출");
    }
    private static Parsed missing(String id, String profile, String status) {
        return new Parsed(id, profile, "additional_instructions", null, status);
    }
    private static Parsed unavailable(String status) { return new Parsed(null, null, null, null, status); }
}
