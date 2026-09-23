package com.dbcompanion;

import com.dbcompanion.common.db.ProfileAuditParser;
import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.model.ProfileHistory.Target;
import com.dbcompanion.service.ProfileHistoryService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Grammar/SQL contracts only: no substitute database or mocked audit trail. */
class ProfileHistoryTest {
    private static final String OWNER = "C##CLOUD$SERVICE";
    private String sql(String value) { return "BEGIN DBMS_CLOUD_AI.SET_ATTRIBUTE('my_profile','additional_instructions'," + value + "); END;"; }
    @Test void missingAuditPrivilegesAreUnknownNotOffAndOtherFailuresAreNotHidden() {
        for (int code : new int[]{942, 1031}) {
            var failure = new RuntimeException(new java.sql.SQLException("audit view access", "72000", code));
            assertThat(ProfileHistoryService.auditAccessDenied(failure)).isTrue();
            assertThat(ProfileHistoryService.<Boolean>auditCapability(() -> { throw failure; })).isNull();
        }
        assertThat(ProfileHistoryService.auditCapability(() -> false)).isFalse();
        assertThat(ProfileHistoryService.auditCapability(() -> true)).isTrue();
        for (RuntimeException failure : new RuntimeException[]{
                new RuntimeException(new java.sql.SQLException("invalid identifier", "42000", 904)),
                new RuntimeException(new java.sql.SQLException("connection lost", "08006", 17002)),
                new IllegalStateException("policy mismatch"), new IllegalArgumentException("ORA-00942 is only text")}) {
            assertThat(ProfileHistoryService.auditAccessDenied(failure)).isFalse();
            assertThatThrownBy(() -> ProfileHistoryService.auditCapability(() -> { throw failure; })).isSameAs(failure);
        }
    }
    @Test void literalsPreserveTextAndDoNotClaimAppliedSuccess() {
        var value = ProfileAuditParser.parse(sql("'한글 ''인용''\n<script>x</script>'") + '\0', null, OWNER).getFirst();
        assertThat(value.profile()).isEqualTo("MY_PROFILE");
        assertThat(value.value()).isEqualTo("한글 '인용'\n<script>x</script>");
        assertThat(value.quality()).isEqualTo("LITERAL");
        assertThat(ProfileAuditParser.parse(sql("q'[a'b, => END;]'") , null, OWNER).getFirst().value()).isEqualTo("a'b, => END;");
        assertThat(ProfileAuditParser.parse(sql("NULL"), null, OWNER).getFirst().value()).isNull();
    }
    @Test void realClobGrammarAndTruncatedBindsRemainUnverified() {
        String text = "DECLARE v_value CLOB := :1 ; BEGIN /* test */ \"C##CLOUD$SERVICE\".\"DBMS_CLOUD_AI\".SET_ATTRIBUTE(profile_name=>'P',attribute_name=>'additional_instructions',attribute_value=>v_value); END;\0";
        var result = ProfileAuditParser.parse(text, " #1(39):짧은 CLOB 진단: '확인'된 내용만 사용하세요.\n두 번째 줄입니다.", OWNER).getFirst();
        assertThat(result.quality()).isEqualTo("BIND_UNVERIFIED");
        assertThat(result.value()).contains("두 번째 줄");
        assertThat(ProfileAuditParser.parse(sql(":1"), "#1(3):abc", OWNER).getFirst().quality()).isEqualTo("BIND_UNVERIFIED");
        assertThat(ProfileAuditParser.parse(sql(":1"), "#1(999):abc", OWNER).getFirst().value()).isEqualTo("abc");
        assertThat(ProfileAuditParser.parse(sql(":1"), "#1(5):a #2(1):b", OWNER).getFirst().quality()).isEqualTo("UNAVAILABLE");
        assertThat(ProfileAuditParser.parse(sql(":1"), null, OWNER).getFirst().quality()).isEqualTo("UNAVAILABLE");
    }
    @Test void allStaticCallsInOneBlockAreRequestsNotVersions() {
        var rows = ProfileAuditParser.parse("""
                BEGIN
                 DBMS_CLOUD_AI.CREATE_PROFILE(profile_name=>'P', attributes=>'{}', status=>'DISABLED');
                 DBMS_CLOUD_AI.SET_ATTRIBUTES('P','{"comments":"x"}');
                 DBMS_CLOUD_AI.ENABLE_PROFILE('P');
                 DBMS_CLOUD_AI.DISABLE_PROFILE('P');
                 DBMS_CLOUD_AI.DROP_PROFILE('P',false);
                END;
                """, null, OWNER);
        assertThat(rows).hasSize(5);
        assertThat(rows.get(1).value()).isEqualTo("{\"comments\":\"x\"}");
        assertThat(rows.get(4).operation()).isEqualTo("DROP_PROFILE");
    }
    @Test void uncertainAttributionOrProgramsAreKeptUnclassified() {
        for (String value : new String[]{
                sql("'a'||'b'"), sql("'broken"), sql("'value'").replace("BEGIN", "BEGIN IF TRUE THEN"),
                sql("'x'").replace("DBMS_CLOUD_AI", "OTHER.DBMS_CLOUD_AI"),
                sql("'x'").replace("'my_profile'", ":1"), sql("'x'") + sql("'y'"),
                sql("'x'").replace("BEGIN", "BEGIN EXECUTE IMMEDIATE"),
                sql("'x'").replace("'additional_instructions'", ":2"),
                "BEGIN APP_WRAPPER.UPDATE_PROFILE('P'); END;", sql("'x'").replace("END", "EXCEPTION WHEN OTHERS THEN NULL; END")})
            assertThat(ProfileAuditParser.parse(value, "#1(1):P", OWNER)).as(value).isEmpty();
        assertThat(ProfileAuditParser.parse(sql("'" + "x".repeat(131072) + "'"), null, OWNER)).isEmpty();
    }
    @Test void sqlQuotesIdentifiersAndNeverTouchesBusinessProfileOrOtherPolicies() {
        assertThat(ProfileHistorySql.policy("A")).hasSize(27).isNotEqualTo(ProfileHistorySql.policy("B"));
        assertThat(ProfileHistorySql.toggle("A\"; X", true)).endsWith(" BY \"A\"\"; X\"");
        assertThat(ProfileHistorySql.createPolicy("A", OWNER)).contains("ACTIONS EXECUTE ON \"C##CLOUD$SERVICE\".\"DBMS_CLOUD_AI\"");
        assertThat(ProfileHistorySql.toggle("A", false)).startsWith("NOAUDIT POLICY").doesNotContain("DROP");
        assertThat(ProfileHistorySql.tables("A").values()).allSatisfy(ddl -> assertThat(ddl).startsWith("CREATE TABLE \"A\".").doesNotContain("CREATE OR REPLACE", "GRANT", "TRIGGER"));
        assertThat(ProfileHistorySql.SOURCE_KEY).contains("EVENT_TIMESTAMP_UTC", "SESSIONID", "ENTRY_ID", "STATEMENT_ID", "DBID", "INSTANCE_ID");
    }
    @Test void selectedSchemaAndExistingProfileAccessPolicyAreRequired() {
        ProfileHistoryService.validate("ADMIN", "APP", new Target("APP", "P"));
        ProfileHistoryService.validate("APP", "APP", new Target("APP", null));
        assertThatThrownBy(() -> ProfileHistoryService.validate("APP", "APP", new Target("OTHER", null))).hasMessage("Schema changed");
        assertThatThrownBy(() -> ProfileHistoryService.validate("OTHER", "APP", new Target("APP", "P"))).hasMessage("Profile schema restricted");
        assertThatThrownBy(() -> ProfileHistoryService.validate("ADMIN", "APP", new Target("APP", " "))).hasMessage("Invalid profile");
    }
}
