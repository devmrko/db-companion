package com.dbcompanion;

import com.dbcompanion.common.db.HistoryPermissions;
import com.dbcompanion.common.db.HistorySql;
import com.dbcompanion.model.MetadataEdit.Target;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HistoryAuditUpgradeTest {
    private final Target target = new Target("APP", "T", null);

    @Test void mviewSnapshotUsesTheRightDictionaryWithoutDuplicatingItsBackingTable() {
        String sql = HistorySql.auditBody(target, false).sql();
        assertThat(sql).contains("FROM sys.all_mview_comments", "mview_name = p_table", "UNION ALL",
                "FROM sys.all_tab_comments c", "NOT EXISTS (SELECT 1 FROM sys.all_mviews", "v_count <> 1",
                "owner = 'APP'", "Table metadata is not visible");
        assertThat(sql).doesNotContain("COALESCE", "COMMIT;", "AUTONOMOUS_TRANSACTION", "WHEN OTHERS THEN NULL");
    }

    @Test void oldBodyIsRecognizedExactlyAndOnlySnapshotQueryChanges() {
        var legacy = HistorySql.auditBody(target, true);
        var current = HistorySql.auditBody(target, false);
        assertThat(HistorySql.sourceMatches(legacy, legacy.sql())).isTrue();
        assertThat(HistorySql.sourceMatches(current, legacy.sql())).isFalse();
        assertThat(HistorySql.sourceMatches(legacy, legacy.sql().replace("v_count <> 1", "v_count < 0"))).isFalse();
        assertThat(current.sql().substring(current.sql().indexOf("    FOR r IN (SELECT column_name")))
                .isEqualTo(legacy.sql().substring(legacy.sql().indexOf("    FOR r IN (SELECT column_name")));
    }

    @Test void explicitUpgradeReplacesOnlyThePackageBody() {
        assertThat(HistorySql.replaceAuditBody(target)).startsWith("CREATE OR REPLACE PACKAGE BODY \"APP\".\"DBC_METADATA_AUDIT\"")
                .doesNotContain("GRANT ", "DROP ", "ALTER TABLE", "ALTER TRIGGER");
        assertThat(HistorySql.assets(target, "INSTALLER")).allMatch(a -> !a.sql().contains("OR REPLACE"));
    }

    @Test void packageUpgradeRequiresCreateProcedurePrivilegeNotJustTriggerManagement() {
        var privileges = Set.of("ADMINISTER DATABASE TRIGGER", "ALTER ANY TRIGGER", "INSERT ANY TABLE", "UPDATE ANY TABLE");
        var access = new HistoryPermissions.Access("ADMIN", privileges, Set.of("ADMINISTER DATABASE TRIGGER"), Set.of(), true, null);
        assertThat(HistoryPermissions.evaluate(target, "ADMIN", access, List.of(HistorySql.auditBody(target, false)), false, true).missing())
                .contains("CREATE ANY PROCEDURE");
    }
}
