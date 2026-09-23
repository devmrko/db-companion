package com.dbcompanion;

import com.dbcompanion.service.WalletCatalogService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP and local TNS fixtures. No DB login, connection, AI or persistent writes. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiWalletHttpTest {
    private static final Path ROOT = fixture();
    static Path fixture() {
        try {
            var root = Files.createTempDirectory("db-companion-wallet-http-");
            for (String name : new String[]{"A", "B"}) {
                var dir = Files.createDirectory(root.resolve("Wallet_" + name));
                Files.writeString(dir.resolve("tnsnames.ora"), name.toLowerCase() + "_low = (DESCRIPTION=(CONNECT_DATA=(SERVICE_NAME=not-used)))\n");
            }
            return root;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry props) {
        props.add("app.oracle.wallet-path", () -> "/ignored/legacy");
        props.add("app.oracle.wallet-paths", () -> new JsonMapper().writeValueAsString(
                java.util.List.of(ROOT.resolve("Wallet_A").toString(), ROOT.resolve("Wallet_B").toString())));
    }
    @AfterAll static void cleanup() throws Exception {
        for (String name : new String[]{"A", "B"}) {
            Files.deleteIfExists(ROOT.resolve("Wallet_" + name).resolve("tnsnames.ora"));
            Files.deleteIfExists(ROOT.resolve("Wallet_" + name));
        }
        Files.deleteIfExists(ROOT);
    }
    @LocalServerPort int port;
    @Autowired WalletCatalogService wallets;
    HttpClient client;
    @BeforeEach void setup() { client = HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build(); }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(Map<String,String> fields) throws Exception {
        var body = fields.entrySet().stream().map(e -> enc(e.getKey())+"="+enc(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/login")).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    String id(int i) { return wallets.wallets().get(i).id(); }
    String csrf() throws Exception {
        var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(get("/login").body());
        assertThat(match.find()).isTrue(); return match.group(1);
    }
    String form(String html, String marker) {
        return Pattern.compile("<form\\b[^>]*"+marker+"[^>]*>.*?</form>",Pattern.DOTALL).matcher(html).results().findFirst().orElseThrow().group();
    }
    @Test void selectingWalletRendersOnlyItsAliasesAndNeverLeaksPaths() throws Exception {
        var first = get("/login");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("a_low",id(0),id(1),"/js/login.mjs").doesNotContain("b_low",ROOT.toString(),"/ignored/legacy","th:","??login.");
        var second = get("/login?walletId="+id(1));
        assertThat(second.body()).contains("b_low","value=\""+id(1)+"\" selected=\"selected\"").doesNotContain("a_low",ROOT.toString());
        assertThat(second.headers().firstValue("cache-control").orElse("")).contains("no-store");
        assertThat(get("/login?walletId="+id(0)).body()).contains("a_low").doesNotContain("b_low");
    }
    @Test void walletGetFormNeverContainsCredentialFieldsOrPostsLogin() throws Exception {
        var html = get("/login").body(); var selector = form(html,"data-wallet-form"); var login = form(html,"data-login-form");
        assertThat(selector).contains("method=\"get\"","name=\"walletId\"").doesNotContain("name=\"password\"","name=\"username\"","name=\"tnsAlias\"");
        assertThat(login).contains("method=\"post\"","name=\"walletId\" value=\""+id(0)+"\"","name=\"_csrf\"");
    }
    @Test void failedLoginKeepsChosenWalletAndServiceWithoutEchoingPassword() throws Exception {
        var response = post(Map.of("walletId",id(1),"tnsAlias","b_low","username","","password","do-not-echo-this","_csrf",csrf()));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("b_low","value=\"b_low\" selected=\"selected\"","name=\"walletId\" value=\""+id(1)+"\"")
                .doesNotContain("a_low","do-not-echo-this");
    }
    @Test void forgedMissingAndCrossWalletSelectionsAreRejectedBeforeConnecting() throws Exception {
        for (var selection : java.util.List.of(Map.of("walletId","","tnsAlias","a_low"),Map.of("walletId","/tmp/forged","tnsAlias","a_low"),Map.of("walletId",id(0),"tnsAlias","b_low"))) {
            var fields = new java.util.HashMap<>(selection); fields.putAll(Map.of("username","APP","password","do-not-echo-this","_csrf",csrf()));
            var response = post(fields);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).doesNotContain("do-not-echo-this", "/tmp/forged",ROOT.toString());
            assertThat(response.body()).contains(selection.get("walletId").equals(id(0)) ? "Wallet에 등록된 접속 서비스를 선택해 주세요." : "등록된 Wallet을 선택해 주세요.");
        }
    }
    @Test void invalidPageSelectionCannotFallBackToDifferentDatabaseAndCsrfStillApplies() throws Exception {
        var response = get("/login?walletId=not-registered");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("등록된 Wallet을 선택해 주세요.","disabled=\"disabled\"").doesNotContain("a_low","b_low");
        assertThat(post(Map.of("walletId",id(0),"username","APP","password","irrelevant","tnsAlias","a_low")).statusCode()).isEqualTo(403);
        assertThat(get("/js/login.mjs").statusCode()).isEqualTo(200);
        assertThat(get("/").statusCode()).isEqualTo(302);
    }
}
