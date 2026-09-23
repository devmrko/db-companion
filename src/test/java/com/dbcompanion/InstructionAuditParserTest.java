package com.dbcompanion;

import com.dbcompanion.common.db.InstructionAuditParser;
import com.dbcompanion.common.db.InstructionAuditProbe;
import com.dbcompanion.common.db.InstructionAuditProbe.Transport;
import com.dbcompanion.common.db.ProfileAuditProbeSql.Run;
import com.dbcompanion.service.ProfileAuditProbeService.InstructionAttempt;
import com.dbcompanion.service.ProfileAuditProbeService.Report;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pure text-parser examples, not mocked JDBC responses or simulated database verification. */
class InstructionAuditParserTest {
    private final Run run = new Run("0123456789ABCDEF");
    private String sql(InstructionAuditProbe.Input input) {
        return InstructionAuditProbe.sql(run, "C##CLOUD$SERVICE", input).replace(" := ?;", " := :1 ;");
    }
    @Test void inputSizesExerciseDistinctBoundariesWithoutLocalDisplayTruncation() {
        var inputs = InstructionAuditProbe.inputs();
        assertThat(inputs).hasSize(4);
        assertThat(InstructionAuditProbe.fingerprint(inputs.get(1).value()).utf8Bytes()).isBetween(4097, 32767);
        assertThat(InstructionAuditProbe.fingerprint(inputs.get(3).value()).utf8Bytes()).isGreaterThan(32767);
        for (var input : inputs) {
            assertThat(input.value().length()).isLessThan(65536);
            assertThat(sql(input)).contains(run.profile(), "'additional_instructions'")
                    .doesNotContain("GENERATE", "SET_PROFILE", "credential_name", "object_list");
            if (input.transport() != Transport.LITERAL) assertThat(sql(input)).doesNotContain(input.value());
        }
    }
    @Test void literalExtractsUnicodeQuotesAndNewlinesFromAuditOnly() {
        var input = InstructionAuditProbe.inputs().getFirst();
        var result = InstructionAuditParser.parse(sql(input), null);
        assertThat(result.value()).isEqualTo(input.value());
        assertThat(result.profile()).isEqualTo(run.profile());
        assertThat(result.attribute()).isEqualTo("additional_instructions");
        assertThat(result.caseId()).isEqualTo(input.id());
    }
    @Test void bindValueMayContainQuotesNewlinesAndApparentBindMarkers() {
        var input = new InstructionAuditProbe.Input("VARCHAR_LONG", Transport.VARCHAR2, "한글 '값'\n#2(5):false");
        String prefix = "#1(" + input.value().getBytes(StandardCharsets.UTF_8).length + "):";
        assertThat(InstructionAuditParser.parse(sql(input), prefix + input.value()).value()).isEqualTo(input.value());
        String characters = "#1(" + input.value().codePointCount(0, input.value().length()) + "):";
        assertThat(InstructionAuditParser.parse(sql(input), characters + input.value()).value()).isEqualTo(input.value());
    }
    @Test void missingTruncatedOrUnknownBindIsNotReconstructedFromExpectedInput() {
        var sql = sql(InstructionAuditProbe.inputs().get(1));
        assertThat(InstructionAuditParser.parse(sql, null).value()).isNull();
        assertThat(InstructionAuditParser.parse(sql, "#1(999):short").value()).isNull();
        assertThat(InstructionAuditParser.parse(sql, "[CLOB]").value()).isNull();
        assertThat(InstructionAuditParser.parse(sql, "#1(5):false #2(5):false").value()).isNull();
        assertThat(InstructionAuditParser.parse(sql, "#1(5):fa [TRUNCATED]").value()).isNull();
    }
    @Test void arbitraryOrMultiStatementProgramsAreNotInterpreted() {
        var sql = sql(InstructionAuditProbe.inputs().getFirst());
        assertThat(InstructionAuditParser.parse(sql + sql, null).value()).isNull();
        assertThat(InstructionAuditParser.parse(sql.replace("'additional_instructions'", "'comments'"), null).value()).isNull();
        assertThat(InstructionAuditParser.parse("BEGIN user_package.update_profile; END;", null).value()).isNull();
        assertThat(InstructionAuditParser.parse(null, null).value()).isNull();
    }
    @Test void fingerprintsDistinguishNullEmptyAndMultibyteText() {
        assertThat(InstructionAuditProbe.fingerprint(null)).isNull();
        assertThat(InstructionAuditProbe.fingerprint("").utf8Bytes()).isZero();
        assertThat(InstructionAuditProbe.fingerprint("한글").characters()).isEqualTo(2);
        assertThat(InstructionAuditProbe.fingerprint("한글").utf8Bytes()).isEqualTo(6);
        assertThat(InstructionAuditProbe.fingerprint("한글").sha256()).hasSize(64);
    }
    @Test void extractionDoesNotClaimAppliedSuccessAndAmbiguousRowsAreRejected() {
        var input = InstructionAuditProbe.inputs().getFirst();
        var attempt = new InstructionAttempt(input, input.value(), "ORA error");
        var row = Map.of("SQL_TEXT", sql(input));
        var report = new Report(run, null, List.of(), List.of(), List.of(row), List.of(), true, List.of(attempt));
        assertThat(attempt.applied()).isFalse();
        assertThat(report.instructionEvidence().getFirst().auditMatches()).isTrue();
        var duplicate = new Report(run, null, List.of(), List.of(), List.of(row, row), List.of(), true, List.of(attempt));
        assertThat(duplicate.instructionEvidence().getFirst().auditMatches()).isFalse();
        assertThat(duplicate.instructionEvidence().getFirst().auditRows()).isEqualTo(2);
    }
    @Test void observedOracleAuditNulTerminatorAndLeadingBindSpaceAreHandled() {
        // Captured from LOCAL-018 / DFE051F859DA419D. NUL was confirmed in HTTP HTML,
        // not just the DOM, which silently removes it. This is a parser regression only.
        String literal = "BEGIN /* DBC_INSTRUCTION_LITERAL_KO */ \"C##CLOUD$SERVICE\".\"DBMS_CLOUD_AI\".SET_ATTRIBUTE(profile_name => 'DBC_AP_DFE051F859DA419D', attribute_name => 'additional_instructions', attribute_value => '진단 전용: ''승인''된 정의만 사용하세요.\n날짜는 YYYY-MM-DD로 표시하세요.'); END;\0";
        assertThat(InstructionAuditParser.parse(literal, null).value())
                .isEqualTo("진단 전용: '승인'된 정의만 사용하세요.\n날짜는 YYYY-MM-DD로 표시하세요.");
        String clob = "DECLARE v_value CLOB := :1 ; BEGIN /* DBC_INSTRUCTION_CLOB_SHORT */ \"C##CLOUD$SERVICE\".\"DBMS_CLOUD_AI\".SET_ATTRIBUTE(profile_name => 'DBC_AP_DFE051F859DA419D', attribute_name => 'additional_instructions', attribute_value => v_value); END;\0";
        String binds = " #1(39):짧은 CLOB 진단: '확인'된 내용만 사용하세요.\n두 번째 줄입니다.";
        var parsed = InstructionAuditParser.parse(clob, binds);
        assertThat(parsed.value()).isEqualTo("짧은 CLOB 진단: '확인'된 내용만 사용하세요.\n두 번째 줄입니다.");
        assertThat(InstructionAuditProbe.fingerprint(parsed.value()).sha256())
                .isEqualTo("133ea6c4431e619df7442b9b8e2c2d8b9aa2a42503b8ba52996f2ae7c93df0c7");
        assertThat(InstructionAuditParser.parse(clob.replace("DECLARE", "DECL\0ARE"), binds).value()).isNull();
    }
    @Test void matchingCapturedLengthDoesNotProveTheEntireInputWasAudited() {
        var input = InstructionAuditProbe.inputs().get(1);
        // Controlled truncation regression using the captured header/character count.
        // It is not an additional Oracle execution or a saved raw audit row.
        var row = Map.of("SQL_TEXT", sql(input) + "\0", "SQL_BINDS", " #1(1858):" + input.value().substring(0, 1858));
        var report = new Report(run, null, List.of(), List.of(), List.of(row), List.of(), true,
                List.of(new InstructionAttempt(input, input.value(), null)));
        var item = report.instructionEvidence().getFirst();
        assertThat(item.attempt().applied()).isTrue();
        assertThat(item.parsed().value()).hasSize(1858);
        assertThat(item.auditMatches()).isFalse();
    }
}
