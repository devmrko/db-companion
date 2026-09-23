package com.dbcompanion;

import com.dbcompanion.common.db.ProfileAuditProbeSql;
import com.dbcompanion.common.db.ProfileAuditProbeSql.Run;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProfileAuditProbeSqlTest {
    private final Run run = new Run("0123456789ABCDEF");
    @Test void namesCannotBeArbitraryDatabaseObjects() {
        assertThat(run.policy()).isEqualTo("DBC_PA_0123456789ABCDEF");
        assertThat(run.profile()).isEqualTo("DBC_AP_0123456789ABCDEF");
        assertThatThrownBy(() -> new Run("TENANT_PROFILE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Run("0123456789ABCDE'" )).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void auditIsRestrictedToOneClientSessionInstanceAndLogin() {
        var sql = ProfileAuditProbeSql.createPolicy(run, "C##CLOUD$SERVICE", "12345", "1");
        assertThat(sql).contains("ACTIONS EXECUTE ON \"C##CLOUD$SERVICE\".\"DBMS_CLOUD_AI\"",
                "CLIENT_IDENTIFIER", run.client(), "SESSIONID", "12345", "INSTANCE", "EVALUATE PER STATEMENT");
        assertThat(sql).doesNotContain("ALL ON", "GRANT", "CONTAINER=ALL");
        assertThat(ProfileAuditProbeSql.audit(run, "APP_USER", true)).endsWith(" BY \"APP_USER\"");
        assertThat(ProfileAuditProbeSql.audit(run, "APP_USER", false)).startsWith("NOAUDIT POLICY");
        assertThatThrownBy(() -> ProfileAuditProbeSql.condition(run, "1 OR 1=1", "1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void profileContainsNoCredentialsOrBusinessObjectsAndDoesNotCallAi() {
        var sql = ProfileAuditProbeSql.createProfile(run, "CLOUD_OWNER");
        assertThat(sql).contains("status => 'disabled'", "\"comments\":false", run.description(), run.profile());
        assertThat(sql).doesNotContain("credential_name", "object_list", "GENERATE", "SET_PROFILE", "OR REPLACE");
    }
    @Test void testStatementsAndCleanupOnlyUseTheGeneratedProfile() {
        assertThat(ProfileAuditProbeSql.setComments(run, "CLOUD_OWNER", true)).contains(run.profile(), "'comments'", "TRUE");
        assertThat(ProfileAuditProbeSql.consecutiveComments(run, "CLOUD_OWNER")).contains("TRUE);", "FALSE);");
        assertThat(ProfileAuditProbeSql.boundComments("CLOUD_OWNER"))
                .contains("DECLARE v_profile VARCHAR2(128) := ?; v_value VARCHAR2(32) := ?;",
                        "profile_name => v_profile", "attribute_value => v_value")
                .doesNotContain("CAST(");
        assertThat(ProfileAuditProbeSql.dropProfile(run, "CLOUD_OWNER")).contains("DROP_PROFILE", run.profile()).doesNotContain("force");
        assertThat(ProfileAuditProbeSql.dropPolicy(run)).isEqualTo("DROP AUDIT POLICY \"" + run.policy() + "\"");
        assertThat(ProfileAuditProbeSql.literal("O'Brien")).isEqualTo("'O''Brien'");
    }
}
