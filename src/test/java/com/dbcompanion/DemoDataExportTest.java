package com.dbcompanion;

import com.dbcompanion.common.config.OracleSettings;
import com.dbcompanion.model.DemoDataPrompt;
import com.dbcompanion.service.TnsCatalogService;
import com.dbcompanion.service.WalletCatalogService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class DemoDataExportTest {
    @TempDir Path root;
    WalletCatalogService wallets;
    TnsCatalogService catalog;
    final JsonMapper json = new JsonMapper();

    @BeforeEach void setup() throws Exception {
        var a = Files.createDirectory(root.resolve("Wallet_A"));
        var b = Files.createDirectory(root.resolve("Wallet_B"));
        Files.writeString(a.resolve("tnsnames.ora"), "# hidden = (comment)\na_low = (DESCRIPTION=(CONNECT_DATA=(SERVICE_NAME=fixture)))\n");
        Files.writeString(b.resolve("tnsnames.ora"), "b_low = (DESCRIPTION=(CONNECT_DATA=(SERVICE_NAME=fixture)))\n");
        Files.writeString(a.resolve("cwallet.sso"), "synthetic-secret-never-export");
        wallets = new WalletCatalogService(new OracleSettings("", json.writeValueAsString(List.of(a.toString(), b.toString()))));
        catalog = new TnsCatalogService(wallets);
    }
    DemoDataPrompt.ExportInput input(String prefix, String start, String end, int rows, String preset, String description) {
        return new DemoDataPrompt.ExportInput(wallets.wallets().getFirst().id(), "a_low", preset, end, prefix,
                new DemoDataPrompt.Input(description, "Orders by day?", "Exclude fictional cancelled orders", start, rows));
    }
    DemoDataPrompt.ExportInput valid() { return input("demo_", "2025-01-01", "2025-12-31", 120, "commerce", "Fictional orders"); }
    DemoDataPrompt.Output export(DemoDataPrompt.ExportInput input) { return DemoDataPrompt.export("SCENARIO_USER", "SCENARIO_USER", input, catalog); }

    @Test void exportKeepsTheBusinessCriteriaAndExplicitNonSecretContract() {
        var output = export(valid());
        var config = json.readTree(output.config());
        assertThat(config.propertyNames()).containsExactlyInAnyOrder("version", "walletPath", "tnsAlias", "preset", "description", "questions", "rules", "rows", "startDate", "endDate", "username", "prefix", "outputDirectory");
        assertThat(config.get("username").stringValue()).isEqualTo("SCENARIO_USER");
        assertThat(config.get("prefix").stringValue()).isEqualTo("DEMO_");
        assertThat(config.get("walletPath").stringValue()).isEqualTo(root.resolve("Wallet_A").toString());
        assertThat(config.get("questions").stringValue()).isEqualTo("Orders by day?");
        assertThat(output.config()).doesNotContainIgnoringCase("password").doesNotContain("synthetic-secret-never-export", "DESCRIPTION", "b_low");
        assertThat(output.prompt()).doesNotContain("synthetic-secret-never-export", root.toString());
    }

    @Test void exportRetainsDedicatedAccountAndSameSchemaRules() {
        for (String owner : List.of("SYS", "ADMIN", "SYSTEM", "PUBLIC", "BAD;DROP", "A.B"))
            assertThatThrownBy(() -> DemoDataPrompt.export(owner, owner, valid(), catalog)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoDataPrompt.export("SCENARIO_USER", "OTHER", valid(), catalog)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsInvalidInputsBeforeFileGeneration() {
        assertThatThrownBy(() -> export(null)).isInstanceOf(IllegalArgumentException.class);
        for (String prefix : List.of("X", "_X_", "A%;_", "A.B_", "A-", "A_________________", "X_\n"))
            assertThatThrownBy(() -> export(input(prefix,"2025-01-01","2025-12-31",1,"school","Fictional school"))).isInstanceOf(IllegalArgumentException.class);
        for (int count : new int[]{0,-1,10_001})
            assertThatThrownBy(() -> export(input("D_","2025-01-01","2025-12-31",count,"school","Fictional school"))).isInstanceOf(IllegalArgumentException.class);
        for (String date : List.of("2025-02-29", "bad", "1899-12-31", "2026-01-01"))
            assertThatThrownBy(() -> export(input("D_",date,"2025-12-31",1,"school","Fictional school"))).isInstanceOf(IllegalArgumentException.class);
        for (String preset : new String[]{null,"unknown"})
            assertThatThrownBy(() -> export(input("D_","2025-01-01","2025-12-31",1,preset,"Fictional school"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> export(input("D_","2025-01-01",null,1,"school","Fictional school"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> export(input("D_","2025-01-01","2025-12-31",1,"custom",""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> export(input("D_","2025-01-01","2025-12-31",1,"custom","x".repeat(2001)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsCrossWalletAliasesAndUnregisteredPaths() {
        var good = valid();
        for (String id : List.of("/tmp/forged", wallets.wallets().get(1).id()))
            assertThatThrownBy(() -> export(new DemoDataPrompt.ExportInput(id, good.tnsAlias(), good.preset(), good.endDate(), good.prefix(), good.criteria())))
                    .isInstanceOf(com.dbcompanion.common.exception.AppException.class);
    }

    @Test void unifiedPromptRetainsBothSafetyAndTrainingWorkflow() {
        String prompt = export(valid()).prompt();
        assertThat(prompt).contains("sql -V", "BEFORE installing", "ONLY through", "sqlcl.sh", "Never read", "own schema", "SUBSTR", "CASCADE CONSTRAINTS", "primary/foreign", "column comments", "business term", "DBMS_RANDOM", "ten business", "reference values calculated", "Never invent", "Do not create users",
                "Do not alter or delete", "Never DROP or TRUNCATE", "01-schema.sql", "02-data.sql", "03-verify.sql", "04-business-rules.md", "SESSION_USER", "CURRENT_SCHEMA", "CUSTOM Semantic Context", "untrusted data");
        assertThat(prompt).doesNotContain("before dropping", "only these tables", "correct the cause and rerun");
    }
}
