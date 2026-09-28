package com.dbcompanion;

import com.dbcompanion.common.db.HistorySql;
import com.dbcompanion.model.MetadataEdit.Target;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HistorySqlTest {
    @Test void triggerIdentityIsStableShortAndTableSpecific() {
        var one = new Target("APP", "ONE", null);
        assertThat(HistorySql.triggerName(one, true)).hasSize(29).startsWith("DBC_MH_B_")
                .isEqualTo(HistorySql.triggerName(one, true))
                .isNotEqualTo(HistorySql.triggerName(new Target("APP", "TWO", null), true))
                .isNotEqualTo(HistorySql.triggerName(one, false));
    }
    @Test void triggerStartsDisabledAndFiltersExactOwnerAndTable() {
        var target = new Target("A'B", "T'X", null);
        String sql = HistorySql.trigger(target, "INSTALLER", true).sql();
        assertThat(sql).contains("BEFORE COMMENT OR ALTER ON DATABASE\nDISABLE", "ora_dict_obj_owner = 'A''B'", "v_name = 'T''X'", ".capture_before('T''X')");
        assertThat(sql).startsWith("CREATE TRIGGER \"INSTALLER\".").contains("\"A'B\".\"DBC_METADATA_AUDIT\"");
        assertThat(HistorySql.switchTrigger(target, "INSTALLER", true, false)).startsWith("ALTER TRIGGER \"INSTALLER\".").endsWith(" DISABLE").doesNotContain("ALTER TABLE");
    }
    @Test void assetsNeverOverwriteAndHistoryFailureIsNotSwallowed() {
        var assets = HistorySql.assets(new Target("APP", "T", null), "INSTALLER");
        assertThat(assets).hasSize(7);
        for (var asset : assets) assertThat(asset.sql()).startsWith("CREATE ").doesNotContain("OR REPLACE", "{{", "AUTONOMOUS_TRANSACTION", "COMMIT;", "WHEN OTHERS THEN NULL");
        String body = assets.stream().filter(a -> a.type().equals("PACKAGE BODY")).findFirst().orElseThrow().sql();
        assertThat(body).contains("FOR UPDATE", "RAISE;", "before_json", "p_present");
        assertThat(assets.get(1).sql()).contains("GENERATED ALWAYS AS IDENTITY", "BEFORE_JSON IS JSON", "AFTER_JSON IS JSON");
        assertThat(assets.get(0).sql()).contains("TRIGGER_OWNER VARCHAR2(128 BYTE)");
        assertThat(assets.subList(0, 5)).allMatch(a -> a.owner().equals("APP"));
        assertThat(assets.subList(5, 7)).allMatch(a -> a.owner().equals("INSTALLER"));
    }
    @Test void upgradeOnlyChangesTheAppTrackingTable() {
        assertThat(HistorySql.upgradeTracking(new Target("APP", "BUSINESS_TABLE", null)))
                .isEqualTo("ALTER TABLE \"APP\".\"DBC_METADATA_TRACKING\" ADD (TRIGGER_OWNER VARCHAR2(128 BYTE))")
                .doesNotContain("BUSINESS_TABLE", "UPDATE", "DROP", "GRANT");
    }
    // Oracle source shape from an installation diagnostic; identifiers and derived hashes anonymized.
    private String observed(boolean before) throws Exception {
        return new ClassPathResource("db/history/observed-" + (before ? "before" : "after") + "-trigger.sql").getContentAsString(StandardCharsets.UTF_8);
    }
    @Test void observedOracleSourcesMatchWithoutHeaderFlagAndMarker() throws Exception {
        var target = new Target("DEMO_APP", "DEMO_GAME_ALIAS", null);
        for (boolean before : new boolean[]{false, true}) {
            var asset = HistorySql.legacyTrigger(target, "ADMIN", before);
            assertThat(HistorySql.sourceMatches(asset, observed(before))).isTrue();
            assertThat(HistorySql.sourceMatches(asset, asset.sql())).isTrue();
            assertThat(HistorySql.sourceMatches(asset, asset.sql().replace("\nDISABLE\n", "\nENABLE\n"))).isTrue();
            assertThat(HistorySql.triggerVersion(target, "ADMIN", before, observed(before))).isEqualTo(HistorySql.TriggerVersion.LEGACY);
            assertThat(HistorySql.sourceMatches(HistorySql.trigger(target, "ADMIN", before), observed(before))).isFalse();
        }
    }
    @Test void comparisonStillChecksIdentityEventsFilterAndExecutableBody() throws Exception {
        var asset = HistorySql.legacyTrigger(new Target("DEMO_APP", "DEMO_GAME_ALIAS", null), "ADMIN", false);
        String actual = observed(false);
        for (String changed : new String[]{
                actual.replace("\"ADMIN\"", "\"OTHER\""),
                actual.replace("DBC_MH_A_", "DBC_MH_B_"),
                actual.replace("AFTER COMMENT", "BEFORE COMMENT"),
                actual.replace("ON DATABASE", "ON SCHEMA"),
                actual.replace("'DEMO_APP'", "'OTHER'"),
                actual.replace("'DEMO_GAME_ALIAS'", "'OTHER_TABLE'"),
                actual.replace(".capture_after(", ".capture_before("),
                actual.replace("END;", "NULL;\nEND;")})
            assertThat(HistorySql.sourceMatches(asset, changed)).isFalse();
    }
    @Test void onlyTheKnownHeaderMarkerIsIgnored() throws Exception {
        var asset = HistorySql.legacyTrigger(new Target("DEMO_APP", "DEMO_GAME_ALIAS", null), "ADMIN", false);
        String actual = observed(false);
        assertThat(HistorySql.sourceMatches(asset, actual.replace("\nBEGIN\n", "\n-- changed code\nBEGIN\n"))).isFalse();
        assertThat(HistorySql.sourceMatches(asset, actual.replace("\nBEGIN\n", "\nBEGIN\n-- DB Companion metadata history v1\n"))).isFalse();
        assertThat(HistorySql.sourceMatches(asset, "")).isFalse();
    }
    @Test void recompileDoesNotReplacePackageDefinitionOrTouchBusinessTable() {
        assertThat(HistorySql.compileAuditBody(new Target("APP", "BUSINESS_TABLE", null)))
                .isEqualTo("ALTER PACKAGE \"APP\".\"DBC_METADATA_AUDIT\" COMPILE BODY REUSE SETTINGS")
                .doesNotContain("OR REPLACE", "CREATE", "DROP", "GRANT", "BUSINESS_TABLE");
    }
    @Test void columnCommentDispatchChecksTheDictionaryAndRejectsAmbiguousDottedNames() {
        var target = new Target("DEMO_APP", "DEMO_COUNTRY", null);
        String sql = HistorySql.trigger(target, "ADMIN", false).sql();
        assertThat(sql).contains("ora_sysevent = 'COMMENT' AND ora_dict_obj_type = 'COLUMN'",
                "table_name = 'DEMO_COUNTRY'", "table_name || '.' || column_name = v_name", "IF v_count = 1 THEN",
                "INSTR(v_column, '.')", "table_name <> 'DEMO_COUNTRY'", "RAISE_APPLICATION_ERROR(-20084",
                ".capture_after('DEMO_COUNTRY')");
        assertThat(sql).doesNotContain(" LIKE ", "COMMIT", "AUTONOMOUS_TRANSACTION");
    }
    @Test void currentSourceComparisonStillRejectsAnyExecutableFilterChange() {
        var target = new Target("APP", "T", null);
        var asset = HistorySql.trigger(target, "INSTALLER", true);
        String stored = asset.sql().replaceFirst("CREATE ", "").replace("DISABLE\n-- DB Companion metadata history v3\n", "");
        assertThat(HistorySql.triggerVersion(target, "INSTALLER", true, stored)).isEqualTo(HistorySql.TriggerVersion.CURRENT);
        for (String changed : new String[]{stored.replace("'COLUMN'", "'VIEW'"), stored.replace("v_count = 1", "v_count >= 0"),
                stored.replace("'APP'", "'OTHER'"), stored.replace("'T'", "'T2'"), stored.replace("-20084", "-20000"),
                stored.replace("capture_before", "capture_after"), stored.replace("DECLARE\n", "-- unknown\nDECLARE\n")})
            assertThat(HistorySql.triggerVersion(target, "INSTALLER", true, changed)).isEqualTo(HistorySql.TriggerVersion.UNKNOWN);
    }
    @Test void explicitReplacementKeepsTheSameIdentityAndStartsDisabled() {
        var target = new Target("APP", "T", null);
        var sql = HistorySql.replaceTrigger(target, "INSTALLER", false);
        assertThat(sql).startsWith("CREATE OR REPLACE TRIGGER \"INSTALLER\".\"" + HistorySql.triggerName(target, false) + "\"")
                .contains("ON DATABASE\nDISABLE").doesNotContain("ALTER TABLE", "DROP ", "GRANT ");
        assertThat(HistorySql.assets(target, "INSTALLER")).allMatch(a -> !a.sql().contains("OR REPLACE"));
    }
    @Test void v2TableTriggersStayKnownLegacyIncludingOracleStoredHeaders() {
        var target = new Target("APP", "V.X", null);
        for (boolean before : new boolean[]{true, false}) {
            var old = HistorySql.tableTriggerV2(target, "INSTALLER", before);
            assertThat(old.sql()).contains("ora_dict_obj_type = 'TABLE'", "history v2");
            for (String source : new String[]{old.sql(), old.sql().replaceFirst("CREATE ", "").replace("DISABLE\n-- DB Companion metadata history v2\n", "")})
                assertThat(HistorySql.triggerVersion(target, "INSTALLER", before, source)).isEqualTo(HistorySql.TriggerVersion.LEGACY);
            assertThat(HistorySql.triggerVersion(target, "INSTALLER", before, old.sql().replace("v_count = 1", "v_count > 0"))).isEqualTo(HistorySql.TriggerVersion.UNKNOWN);
            assertThat(HistorySql.trigger(target, "INSTALLER", before).sql()).contains("ora_dict_obj_type IN ('TABLE', 'VIEW')", "table_name = 'V.X'", "table_name || '.' || column_name = v_name");
        }
    }
}
