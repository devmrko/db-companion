package com.dbcompanion;

import com.dbcompanion.common.i18n.*;
import com.dbcompanion.controller.LanguageController;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.i18n.LocaleContextHolder;
import static org.assertj.core.api.Assertions.*;

/** Actual HTTP server, no DB connection, mock or simulated authentication. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.oracle.wallet-path=","app.oracle.wallet-paths="})
class I18nHttpTest {
    @LocalServerPort int port;
    HttpClient client;
    @BeforeEach void setup(){client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();}
    HttpResponse<String> get(String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> post(String path,String body)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    String token()throws Exception{
        var m=java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(get("/login").body());
        assertThat(m.find()).isTrue();return URLEncoder.encode(m.group(1),StandardCharsets.UTF_8);
    }
    @Test void fourLanguagesRenderAndClientMessagesMatchWithoutLoggingIn()throws Exception{
        for(var item:Map.of("ko","로그인","en","Sign in","zh-CN","登录","ja","ログイン").entrySet()){
            var response=post("/language","language="+item.getKey()+"&returnTo=%2Flogin&_csrf="+token());
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().allValues("set-cookie").toString()).contains("DB_COMPANION_LANGUAGE","HttpOnly","SameSite=Strict");
            var page=get("/login");assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("lang=\""+item.getKey()+"\"",">"+item.getValue()+"<").doesNotContain("??ui.","th:");
            var messages=get("/i18n/messages.js");assertThat(messages.statusCode()).isEqualTo(200);
            assertThat(messages.body()).contains("globalThis.DB_COMPANION_MESSAGES=",item.getValue()).doesNotContain("</script>");
            assertThat(messages.headers().firstValue("cache-control").orElse("")).contains("no-store");
        }
    }
    @Test void csrfAllowlistAndRedirectBoundaryAreEnforced()throws Exception{
        assertThat(post("/language","language=en").statusCode()).isEqualTo(403);
        assertThat(post("/language","language=fr&_csrf="+token()).statusCode()).isEqualTo(400);
        var response=post("/language","language=en&returnTo=https%3A%2F%2Fexample.invalid&_csrf="+token());
        assertThat(response.headers().firstValue("location").orElse("")).endsWith("/login");
        for(String unsafe:List.of("//example.invalid","/\\example.invalid","/schema","/logout","/language","/login\r\nX: 1"))
            assertThat(LanguageController.safeReturn(unsafe)).isEqualTo("/login");
        assertThat(LanguageController.safeReturn("/vector-search?schema=APP&table=T")).isEqualTo("/vector-search?schema=APP&table=T");
    }
    @Test void languageSurvivesSessionInvalidationAndErrorsAreLocalized()throws Exception{
        post("/language","language=ja&_csrf="+token());
        var response=post("/login","username=&password=&tnsAlias=&_csrf="+token());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("DB ユーザー名とパスワードを入力してください。");
        assertThat(post("/logout","_csrf="+token()).statusCode()).isEqualTo(302);
        assertThat(get("/login").body()).contains("lang=\"ja\"",">ログイン<");
    }
    @Test void languageChangeReturnsToSqlHistoryWithItsSearchAndPageIntact() throws Exception {
        String target="/ai-executions/sql/sources?source=audit&match=generate&text=hello&page=2&load=true";
        var response=post("/language","language=en&returnTo="+URLEncoder.encode(target,StandardCharsets.UTF_8)+"&_csrf="+token());
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location").orElse("")).endsWith(target);
    }
    @Test void sqlHistoryReturnDoesNotBroadenSensitivePageOrApiRedirects() {
        String target="/ai-executions/sql/sources?source=cache&match=generate";
        assertThat(LanguageController.safeReturn(target)).isEqualTo(target);
        for(String path:List.of("/ontology","/ontology-query","/ai-test","/ontology/save","/ontology-query/explain","/ai-test/options","/ai-executions/sql/sources/detail"))
            assertThat(LanguageController.safeReturn(path+"?id=1")).as(path).isEqualTo("/login");
    }
    @Test void cachedNoticeChangesLanguageWithoutReloadAndRawArgumentsRemainUntouched(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var session=new com.dbcompanion.model.DatabaseSession(new com.dbcompanion.model.DatabaseInfo("APP","APP","LOW","DB"),List.of("APP"));
        java.util.function.Supplier<com.dbcompanion.model.MetadataHistory.State> loader=()->{
            calls.incrementAndGet();return new com.dbcompanion.model.MetadataHistory.State(true,false,true,
                    UiNotice.message("ui.406325e31e14","트리거 꺼짐 · 저장된 이력은 유지됩니다."),"B","A","ADMIN",false,"")
                    .withAccess(false,UiNotice.concat(UiNotice.message("ui.ef05200886bd","이력 관리 권한 확인: "),UiNotice.raw("ADMINISTER DATABASE TRIGGER · 이름 日本語")));
        };
        try{
            LocaleContextHolder.setLocale(Locale.ENGLISH);var first=session.historyState("APP","T",false,loader);
            assertThat(first.message()).startsWith("Trigger off");
            LocaleContextHolder.setLocale(Locale.JAPANESE);var second=session.historyState("APP","T",false,loader);
            assertThat(second).isSameAs(first);assertThat(second.message()).startsWith("トリガー OFF");
            assertThat(second.managementMessage()).contains("履歴管理", "ADMINISTER DATABASE TRIGGER · 이름 日本語");
            var json=new tools.jackson.databind.json.JsonMapper().writeValueAsString(second);
            assertThat(json).contains("\"message\":\"トリガー OFF").doesNotContain("messageNotice","managementNotice");
            assertThat(calls.get()).isEqualTo(1);
        }finally{LocaleContextHolder.resetLocaleContext();}
    }
}
