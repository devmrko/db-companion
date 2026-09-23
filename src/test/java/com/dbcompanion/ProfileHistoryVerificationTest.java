package com.dbcompanion;

import com.dbcompanion.service.ProfileHistoryVerificationService;
import com.dbcompanion.common.db.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProfileHistoryVerificationTest {
    @Test void restrictsVerificationToApprovedAdminIdentityAndSchema() {
        ProfileHistoryVerificationService.requireAdmin("ADMIN", "ADMIN");
        assertThatThrownBy(() -> ProfileHistoryVerificationService.requireAdmin("ADMIN", "DEMO_APP")).hasMessageContaining("ADMIN schema");
        assertThatThrownBy(() -> ProfileHistoryVerificationService.requireAdmin("APP", "ADMIN")).hasMessageContaining("Log in as ADMIN");
    }
    @Test void fixedValuesAreDifferentAndAuditParserCanRecoverBothLiteralRequests() {
        var run = new ProfileAuditProbeSql.Run("0123456789ABCDEF");
        var values = ProfileHistoryVerificationService.VALUES;
        assertThat(values).hasSize(2).doesNotHaveDuplicates();
        for (int i = 0; i < values.size(); i++) {
            var input = new InstructionAuditProbe.Input("HISTORY_V" + i, InstructionAuditProbe.Transport.LITERAL, values.get(i));
            var sql = InstructionAuditProbe.sql(run, "C##CLOUD$SERVICE", input);
            assertThat(ProfileAuditParser.parse(sql, null, "C##CLOUD$SERVICE").getFirst().value()).isEqualTo(values.get(i));
            assertThat(sql).doesNotContain("GENERATE", "DROP_PROFILE", "SET_PROFILE");
        }
    }
    @Test void completionRequiresBothSnapshotsRequestsActualReadbackAndAuditOff() {
        var run = new ProfileAuditProbeSql.Run("0123456789ABCDEF");
        assertThat(new ProfileHistoryVerificationService.Report(run, "1", List.of(), List.of(), true, true, 2, 2).completed()).isTrue();
        assertThat(new ProfileHistoryVerificationService.Report(run, "1", List.of(), List.of(), true, false, 2, 2).completed()).isFalse();
        assertThat(new ProfileHistoryVerificationService.Report(run, "1", List.of(), List.of(), true, true, 2, 0).completed()).isFalse();
    }
}
