package com.dbcompanion.model;

import com.dbcompanion.common.i18n.UiMessages;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Base64;
import java.util.List;

/** Memory-resident SQL translations, not a durable execution log. */
public final class AiMappedSql {
    private AiMappedSql() {}
    public static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    /** Effective access from the actual list request, not an inventory of direct grants. */
    public enum Access {
        AVAILABLE(UiMessages.text("ui.ea34d61b41ac", "조회 가능"), "available"),
        REQUIRED(UiMessages.text("ui.a5cae5e8d03b", "권한 필요"), "required"),
        CHECK(UiMessages.text("ui.31da696f6e46", "접근 확인 필요"), "check"),
        UNKNOWN(UiMessages.text("ui.298859a04696", "확인 불가"), "unknown"),
        NOT_CHECKED(UiMessages.text("ui.dc0c487c9693", "미확인"), "unknown");

        private final String label;
        private final String tone;
        Access(String label, String tone) { this.label = label; this.tone = tone; }
        public String getLabel() { return label; }
        public String getTone() { return tone; }
        public static Access failure(int oracleCode) {
            return switch (oracleCode) {
                case 1031 -> REQUIRED;
                case 942 -> CHECK;
                default -> UNKNOWN;
            };
        }
    }

    /** Display only. The application never executes this grant. */
    public static String readGrantExample(String loginUser) {
        String target = "ADMIN".equals(loginUser) ? "DB_USER" : loginUser;
        return "GRANT READ ON SYS.V_$MAPPED_SQL TO "
                + com.dbcompanion.common.db.MetadataSql.identifier(target) + ";";
    }

    public record Query(String sqlId, String question, int page) {
        public Query {
            sqlId = sqlId == null ? "" : sqlId.strip();
            question = question == null ? "" : question.strip();
            if ((!sqlId.isEmpty() && !sqlId.matches("[0-9a-z]{13}")) || question.length() > 500 || page < 1 || page > 1000)
                throw new IllegalArgumentException("Invalid SQL mapping filter or page");
        }
        public static Query parse(String sqlId, String question, String page) {
            return new Query(sqlId, question, page == null || page.isBlank() ? 1 : Integer.parseInt(page));
        }
        public int offset() { return (page - 1) * PAGE_SIZE; }
    }

    /** Nullable DB values use '-' in the transport key. No caller-supplied SQL identifiers. */
    public record Key(String sqlId, String mappedId, String profileId, String conId, String translated) {
        public Key {
            if (sqlId == null || !sqlId.matches("[0-9a-z]{13}") || mappedId == null
                    || !(mappedId.equals("-") || mappedId.matches("[0-9a-z]{13}")))
                throw new IllegalArgumentException("Invalid SQL mapping ID");
            number(profileId); number(conId);
            if (translated == null) throw new IllegalArgumentException("Invalid translation time");
            if (!translated.equals("-")) {
                try {
                    if (!TIME.format(LocalDateTime.parse(translated, TIME)).equals(translated)) throw new IllegalArgumentException("Invalid translation time");
                } catch (java.time.DateTimeException ex) { throw new IllegalArgumentException("Invalid translation time", ex); }
            }
        }
        public String token() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    String.join("|", sqlId, mappedId, profileId, conId, translated).getBytes(StandardCharsets.UTF_8));
        }
        public static Key parse(String token) {
            if (token == null || token.length() > 180 || !token.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid SQL mapping key");
            String[] fields = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 5) throw new IllegalArgumentException("Invalid SQL mapping key");
            var key = new Key(fields[0], fields[1], fields[2], fields[3], fields[4]);
            if (!key.token().equals(token)) throw new IllegalArgumentException("Non-canonical SQL mapping key");
            return key;
        }
        public static long number(String value) {
            if ("-".equals(value)) return -1;
            if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid numeric mapping key");
            return Long.parseLong(value);
        }
    }
    public record Item(String id, String translated, String sqlId, String method, String preview) {}
    public record Page(List<Item> items, int number, boolean hasNext) {
        public static Page of(List<Item> rows, int number) {
            return new Page(List.copyOf(rows.subList(0, Math.min(PAGE_SIZE, rows.size()))), number, rows.size() > PAGE_SIZE);
        }
    }
    public record Detail(String sqlId, String mappedId, String translationProfileId, String conId,
                         String translated, String method, String useCount, String cpuTime, String elapsedTime,
                         String originalSql, String mappedSql) {}
}
