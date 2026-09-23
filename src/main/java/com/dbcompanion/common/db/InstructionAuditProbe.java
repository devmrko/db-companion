package com.dbcompanion.common.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Server-owned test inputs, never business profile contents or HTTP input. */
public final class InstructionAuditProbe {
    private InstructionAuditProbe() {}
    public enum Transport { LITERAL, VARCHAR2, CLOB }
    public record Input(String id, Transport transport, String value) {}
    public record Fingerprint(int characters, int utf8Bytes, String sha256) {}
    public static List<Input> inputs() {
        String line = "진단용 지침입니다. 날짜는 YYYY-MM-DD로 표시하고 근거 없는 내용은 생성하지 마세요.\n";
        return List.of(
                new Input("LITERAL_KO", Transport.LITERAL, "진단 전용: '승인'된 정의만 사용하세요.\n날짜는 YYYY-MM-DD로 표시하세요."),
                new Input("VARCHAR_LONG", Transport.VARCHAR2, "문자열 바인드 시작\n" + line.repeat(80) + "문자열 바인드 끝"),
                new Input("CLOB_SHORT", Transport.CLOB, "짧은 CLOB 진단: '확인'된 내용만 사용하세요.\n두 번째 줄입니다."),
                new Input("CLOB_LONG", Transport.CLOB, "긴 CLOB 시작\n" + line.repeat(800) + "긴 CLOB 끝"));
    }
    public static String sql(ProfileAuditProbeSql.Run run, String owner, Input input) {
        String declaration = switch (input.transport()) {
            case LITERAL -> "";
            case VARCHAR2 -> "DECLARE v_value VARCHAR2(32767) := ?; ";
            case CLOB -> "DECLARE v_value CLOB := ?; ";
        };
        return declaration + "BEGIN /* DBC_INSTRUCTION_" + input.id() + " */ " + ProfileAuditProbeSql.api(owner)
                + ".SET_ATTRIBUTE(profile_name => " + ProfileAuditProbeSql.literal(run.profile())
                + ", attribute_name => 'additional_instructions', attribute_value => "
                + (input.transport() == Transport.LITERAL ? ProfileAuditProbeSql.literal(input.value()) : "v_value") + "); END;";
    }
    public static Fingerprint fingerprint(String value) {
        if (value == null) return null;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            return new Fingerprint(value.codePointCount(0, value.length()), bytes.length,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
