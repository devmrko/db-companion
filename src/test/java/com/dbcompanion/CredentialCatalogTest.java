package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.CredentialCatalog.*;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.CredentialCatalogService;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class CredentialCatalogTest {
    private Entry entry(){return new Entry("APP","CRED","TRUE","TEXT","JSON");}
    private Catalog catalog(String status){return new Catalog(List.of(entry()),"SYS.USER_CREDENTIALS",status,"","now");}
    private Detail detail(String status){return new Detail(entry(),List.of(new Section("profiles",List.of(new Use("APP","P","credential_name")),"USER_CLOUD_AI_PROFILE_ATTRIBUTES",status,"")));}
    private DatabaseSession state(){return new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));}
    @Test void quotedAndUnquotedReferencesAreResolvedWithoutGuessing(){
        for(String value:List.of("cred","APP.CRED","\"APP\".\"CRED\""," app . cred "))assertThat(CredentialCatalog.matches(value,"APP","APP","CRED")).isTrue();
        assertThat(CredentialCatalog.matches("\"MiX\"","APP","APP","MiX")).isTrue();
        assertThat(CredentialCatalog.matches("MiX","APP","APP","MiX")).isFalse();
        assertThat(CredentialCatalog.matches("\"A.B\".\"C\"\"D\"","APP","A.B","C\"D")).isTrue();
        for(String value:Arrays.asList(null,"","OTHER.CRED","A.B.C","CRED;DELETE","CRED@LINK","\"cred\""))assertThat(CredentialCatalog.matches(value,"APP","APP","CRED")).isFalse();
        assertThat(CredentialCatalog.matches("CRED","OTHER","APP","CRED")).isFalse();
    }
    @Test void metadataAndUsageCollectionsAreImmutable(){
        assertThatThrownBy(()->catalog("AVAILABLE").items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->detail("AVAILABLE").sections().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->detail("AVAILABLE").sections().getFirst().items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void cacheIsSessionAndSchemaScopedAndRefreshInvalidatesAllSelectedDetails(){
        var state=state();var calls=new AtomicInteger();java.util.function.Supplier<Detail> load=()->{calls.incrementAndGet();return detail("AVAILABLE");};
        state.credentialDetail("APP","A",load);state.credentialDetail("APP","A",load);state.credentialDetail("OTHER","A",load);assertThat(calls).hasValue(2);
        state.credentials("APP",false,()->catalog("AVAILABLE"));
        state.credentials("APP",true,()->catalog("AVAILABLE"));
        state.credentialDetail("APP","A",load);state.credentialDetail("OTHER","A",load);assertThat(calls).hasValue(3);
        assertThatThrownBy(()->state.credentials("APP",true,()->{throw new IllegalStateException("read failed");})).hasMessage("read failed");
        state.credentialDetail("APP","A",load);assertThat(calls).hasValue(4);
        state().credentialDetail("APP","A",load);assertThat(calls).hasValue(5);
    }
    @Test void knownAccessStateCachesButTransientErrorsDoNot(){
        var state=state();var calls=new AtomicInteger();
        for(int i=0;i<2;i++)state.credentials("APP",false,()->{calls.incrementAndGet();return catalog("ACCESS_REQUIRED");});assertThat(calls).hasValue(1);
        state.credentials("APP",true,()->catalog("ERROR"));
        state.credentials("APP",false,()->{calls.incrementAndGet();return catalog("AVAILABLE");});assertThat(calls).hasValue(2);
        for(int i=0;i<2;i++)state.credentialDetail("APP","A",()->{calls.incrementAndGet();return detail("ERROR");});assertThat(calls).hasValue(4);
        assertThat(detail("ACCESS_REQUIRED").cacheable()).isTrue();assertThat(detail("UNSUPPORTED").cacheable()).isTrue();assertThat(detail("LIMIT").cacheable()).isFalse();
    }
    @Test void realServiceCacheHitDoesNotInitializeHikariOrReadConnection(){
        var source=new com.dbcompanion.common.db.SessionDataSource();
        var service=new CredentialCatalogService(source,new CredentialCatalogRepository(new org.springframework.jdbc.core.JdbcTemplate(source)));
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var login=new com.dbcompanion.common.db.PoolSession(pool,"LOW",()->{})){
            var state=state();login.initialize(state);state.credentials("APP",false,()->catalog("AVAILABLE"));
            var stored=state.credentialDetail("APP","CRED",()->detail("AVAILABLE"));
            assertThat(service.detail(login,"APP","CRED")).isSameAs(stored);
            assertThat(pool.getHikariPoolMXBean()).isNull();
            assertThatThrownBy(()->service.detail(login,"OTHER","CRED")).isInstanceOf(Failure.class);
            assertThatThrownBy(()->service.detail(login,"APP","MISSING")).isInstanceOf(Failure.class);
            assertThatThrownBy(source::getConnection).hasMessage("No authenticated database session");
        }
    }
    @Test void concurrentReadsShareOneLoader() throws Exception {
        var state=state();var calls=new AtomicInteger();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var futures=new ArrayList<java.util.concurrent.Future<Detail>>();
            for(int i=0;i<16;i++)futures.add(executor.submit(()->state.credentialDetail("APP","CRED",()->{calls.incrementAndGet();return detail("AVAILABLE");})));
            var first=futures.getFirst().get();for(var future:futures)assertThat(future.get()).isSameAs(first);
        }
        assertThat(calls).hasValue(1);
    }
    @Test void errorsExposeOnlyOracleCodeAndNoPotentialCredentialValues(){
        for(int code:List.of(942,1031))assertThat(CredentialCatalogRepository.status(new SQLException("token=PRIVATE","",code))).isEqualTo("ACCESS_REQUIRED");
        assertThat(CredentialCatalogRepository.status(new SQLException("column missing","",904))).isEqualTo("UNSUPPORTED");
        assertThat(CredentialCatalogRepository.status(new SQLException("timeout","",1013))).isEqualTo("ERROR");
        assertThat(CredentialCatalogRepository.error(new RuntimeException(new SQLException("password=SECRET","",942)))).isEqualTo("ORA-00942");
    }
    @Test void listSqlProjectsOnlySafeFieldsAndOtherSchemasAreBound(){
        assertThat(CredentialCatalogRepository.listSql(true,false)).contains("SYS.USER_CREDENTIALS","SESSION_USER","CASE WHEN USERNAME","CASE WHEN COMMENTS").doesNotContain("SELECT *","WHERE OWNER");
        assertThat(CredentialCatalogRepository.listSql(false,false)).contains("SYS.DBA_CREDENTIALS WHERE OWNER = ?");
        assertThat(CredentialCatalogRepository.listSql(false,true)).contains("SYS.ALL_CREDENTIALS WHERE OWNER = ?");
    }
    @Test void readOnlyImplementationHasNoSecretExportOrUserChosenSql() throws Exception {
        var path=java.nio.file.Path.of("src/main/java/com/dbcompanion/repository/CredentialCatalogRepository.java");String code=java.nio.file.Files.readString(path);
        assertThat(code).doesNotContain("jdbc.update(","jdbc.execute(","private_key","access_token","getPassword","equals(\"ADMIN\")","SELECT USERNAME","SELECT COMMENTS");
        assertThat(code).contains("WHERE OWNER = ? AND CREDENTIAL_OWNER = ? AND CREDENTIAL_NAME = ?","$.credential_name","ATTRIBUTE_NAME='credential_name'","FETCH FIRST","tool_params.credential_name","new UnsupportedCatalog()");
        String controller=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dbcompanion/controller/CredentialCatalogController.java"));
        assertThat(controller).contains("no-store").doesNotContain("@PostMapping","@PutMapping","@DeleteMapping");
        String service=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dbcompanion/service/CredentialCatalogService.java"));
        assertThat(service).contains("read.setReadOnly(true)","read.setTimeout(10)","finally{source.clear();}","source.bind(session.pool(),session.metadata().info().username())");
    }
    @Test void fourLanguageRealTemplatesEscapeAndPreserveSchemaAndLanguageNavigation(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(String lang:List.of("ko","en","zh-CN","ja")){
            var context=new Context(Locale.forLanguageTag(lang));context.setVariable("activePage","credentials");context.setVariable("info",new DatabaseInfo("APP","APP","LOW","DB"));
            context.setVariable("schemas",List.of("<script>"));context.setVariable("selectedSchema","<script>");context.setVariable("languageReturn","/db/credentials");
            String html=engine.process("credentials",context);
            assertThat(html).contains("Credential ?","data-credentials","&lt;script&gt;","value=\"/db/credentials\"").doesNotContain("??credentials.","th:","data-schema=\"<script>","data-credentials-save");
            assertThat(html.split("value=\"/db/credentials\"",-1)).hasSize(4);
        }
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/db/credentials")).isEqualTo("/db/credentials");
    }
}
