package com.dbcompanion;

import com.dbcompanion.common.config.OracleSettings;
import com.dbcompanion.common.db.OraclePoolFactory;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.service.TnsCatalogService;
import com.dbcompanion.service.WalletCatalogService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class WalletCatalogTest {
    @TempDir Path root;
    final JsonMapper json = new JsonMapper();
    WalletCatalogService registry(String legacy, String plural) { return new WalletCatalogService(new OracleSettings(legacy, plural)); }
    String array(Path... paths) { return json.writeValueAsString(java.util.Arrays.stream(paths).map(Path::toString).toList()); }
    Path wallet(String dir, String alias) throws Exception {
        var path = Files.createDirectories(root.resolve(dir));
        Files.writeString(path.resolve("tnsnames.ora"), "# local test only\n" + alias + " = (DESCRIPTION=(CONNECT_DATA=(SERVICE_NAME=test)))\n");
        Files.writeString(path.resolve("cwallet.sso"), "test placeholder; never used for a connection");
        return path;
    }

    @Test void legacyAndEmptyPluralRemainCompatible() {
        var path = root.resolve("Wallet_LEGACY");
        for (String raw : new String[]{null, "", "  "}) {
            var r = registry(path.toString(), raw);
            assertThat(r.wallets()).hasSize(1);
            assertThat(r.resolve("").path()).isEqualTo(path);
            assertThat(r.resolve(null).name()).isEqualTo("LEGACY");
        }
        assertThatThrownBy(() -> registry("", "").wallets()).isInstanceOf(AppException.class).hasMessageContaining("not configured");
    }

    @Test void pluralWinsAndIdsAreStableAcrossReorderingAndNormalization() {
        var a = root.resolve("Wallet_A"); var b = root.resolve("Wallet_B");
        var r = registry("/ignored/Wallet_OTHER", array(a, b, a.resolve("../Wallet_A")));
        assertThat(r.wallets()).hasSize(2);
        var first = r.wallets().getFirst();
        assertThat(registry("", array(b, a)).resolve(first.id()).path()).isEqualTo(a);
        assertThat(first.option().toString()).doesNotContain(root.toString());
        assertThatThrownBy(() -> r.resolve("")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> r.resolve(a.toString())).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> r.resolve("../Wallet_A")).isInstanceOf(AppException.class);
    }

    @Test void malformedOrUnsafeListsNeverFallBackToLegacy() {
        for (String bad : List.of("[]", "null", "{}", "[1]", "[null]", "[\"\"]", "[\"relative/path\"]", "['/tmp/a']",
                "[\"/tmp/a\"] {}", "[\"/tmp/a\\n\"]", "[\"/\"]", json.writeValueAsString(java.util.Collections.nCopies(33,"/tmp/a")))) {
            assertThatThrownBy(() -> registry(root.toString(), bad).wallets()).as(bad)
                    .isInstanceOf(AppException.class).hasMessage("The registered wallet list is invalid.");
        }
    }

    @Test void originalVariableAlsoAcceptsArrayAndPluralStillWins() {
        var a = root.resolve("Wallet_A"); var b = root.resolve("Wallet_B");
        assertThat(registry(array(a, b), "").wallets()).hasSize(2);
        assertThat(registry(array(a, b), null).wallets()).hasSize(2);
        assertThat(registry(array(a), array(b)).resolve("").path()).isEqualTo(b);
        assertThatThrownBy(() -> registry("['/tmp/a']", "").wallets()).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> registry("[]", "").wallets()).isInstanceOf(AppException.class);
    }

    @Test void duplicateDisplayNamesAreDisambiguatedWithoutParentPaths() {
        var r = registry("", array(root.resolve("one/Wallet_SAME"), root.resolve("two/Wallet_SAME")));
        assertThat(r.wallets().stream().map(WalletCatalogService.Wallet::name).toList()).doesNotHaveDuplicates();
        r.wallets().forEach(w -> assertThat(w.name()).startsWith("SAME · ").doesNotContain("one/", "two/"));
    }

    @Test void catalogReadsOnlySelectedWalletAndRejectsCrossWalletAlias() throws Exception {
        var a = wallet("Wallet_A", "a_low"); var b = wallet("Wallet_B", "b_low");
        var r = registry("", array(a, b)); var tns = new TnsCatalogService(r);
        var aid = r.wallets().get(0).id(); var bid = r.wallets().get(1).id();
        assertThat(tns.options(aid)).extracting(TnsCatalogService.TnsOption::alias).containsExactly("a_low");
        assertThat(tns.options(bid)).extracting(TnsCatalogService.TnsOption::alias).containsExactly("b_low");
        assertThatThrownBy(() -> tns.validate(aid, "b_low")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> tns.options("bad")).isInstanceOf(AppException.class);
        Files.delete(a.resolve("tnsnames.ora"));
        assertThatThrownBy(() -> tns.options(aid)).isInstanceOf(AppException.class);
        assertThat(tns.options(bid)).hasSize(1);
    }

    @Test void identicalTnsNamesUseSeparatePoolPropertiesWithoutDatabaseConnections() throws Exception {
        var a = wallet("Wallet_A", "same_low"); var b = wallet("Wallet_B", "same_low");
        var r = registry("", array(a, b)); var factory = new OraclePoolFactory(new TnsCatalogService(r));
        String original = System.getProperty("oracle.net.tns_admin");
        try (var first = factory.open("APP", "not-a-real-password", "same_low", r.wallets().get(0).id());
             var second = factory.open("APP", "not-a-real-password", "same_low", r.wallets().get(1).id())) {
            assertThat(first.pool()).isNotSameAs(second.pool());
            assertThat(first.pool().getDataSourceProperties().getProperty("oracle.net.tns_admin")).isEqualTo(a.toString());
            assertThat(second.pool().getDataSourceProperties().getProperty("oracle.net.tns_admin")).isEqualTo(b.toString());
            assertThat(first.pool().getDataSourceProperties().getProperty("oracle.net.wallet_location")).isEqualTo(a.toUri().toString());
            assertThat(first.walletId()).isNotEqualTo(second.walletId());
            assertThat(first.walletName()).isEqualTo("A");
            assertThat(first.pool().getHikariPoolMXBean().getTotalConnections()).isZero();
            assertThat(second.pool().getHikariPoolMXBean().getTotalConnections()).isZero();
            assertThat(System.getProperty("oracle.net.tns_admin")).isEqualTo(original);
        } finally { factory.shutdown(); }
    }

    @Test void invalidWalletOrAliasCannotCreatePoolAndSingleWalletAllowsLegacyLogin() throws Exception {
        var a = wallet("Wallet_A", "a_low"); var r = registry(a.toString(), "");
        var factory = new OraclePoolFactory(new TnsCatalogService(r));
        try {
            assertThatThrownBy(() -> factory.open("APP", "password", "a_low", "/tmp/forged")).isInstanceOf(AppException.class);
            assertThatThrownBy(() -> factory.open("APP", "password", "unknown", r.resolve("").id())).isInstanceOf(AppException.class);
            try (var session = factory.open("APP", "password", "a_low")) { assertThat(session.walletName()).isEqualTo("A"); }
        } finally { factory.shutdown(); }
    }
}
