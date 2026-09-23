package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.ExternalSources.*;
import com.dbcompanion.repository.ExternalSourcesRepository;
import com.dbcompanion.service.ExternalSourcesService;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class ExternalSourcesTest {
    private final Entry entry=new Entry("APP","LINK.EXAMPLE","REMOTE");
    private Catalog catalog(String status){return new Catalog(List.of(entry),"SYS.USER_DB_LINKS",status,"");}
    private Detail detail(String status){return new Detail(entry,List.of(new Field("target","dbhost")),List.of(new Section("locations",List.of(new Location("P","SP","DIR","file.csv")),"SYS.USER_EXTERNAL_LOCATIONS",status,"")));}
    @Test void addressRedactionHidesSignedQueriesUserinfoAndParPathsBeforeCaching(){
        for(String raw:List.of("https://user:SECRET@host/p/PAR_SECRET/n/ns/b/bucket/o/file?token=SECRET#SECRET","https://host/bucket/file?X-Amz-Signature=SECRET","https://host/container/file?sig=SECRET","oci://host/p/SECRET/o/data")){
            assertThat(ExternalAddress.location(raw)).endsWith("host/[redacted]").doesNotContain("SECRET","bucket","file","user:");
        }
        assertThat(ExternalAddress.location("https://host:8443/private/file")).isEqualTo("https://host:8443/[redacted]");
        assertThat(ExternalAddress.location("https://host/bad path/SECRET")).isEqualTo("[redacted]");
        assertThat(ExternalAddress.location("javascript://host/SECRET")).isEqualTo("[redacted]");
        assertThat(ExternalAddress.location("s3://bucket/private/path")).isEqualTo("s3://bucket/[redacted]");
    }
    @Test void filenamesAndConnectionsUseAllowlistedPartsOnly(){
        assertThat(ExternalAddress.location("/private/path/data.csv")).isEqualTo("…/data.csv");
        assertThat(ExternalAddress.location("file.csv?token=SECRET")).isEqualTo("[redacted]");
        assertThat(ExternalAddress.location("{\"password\":\"SECRET\"}")).isEqualTo("[redacted]");
        assertThat(ExternalAddress.location(null)).isEmpty();
        assertThat(ExternalAddress.connection("(DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)(HOST=db.example)(PORT=1522))(CONNECT_DATA=(SERVICE_NAME=adb_low)(PASSWORD=SECRET)))"))
            .isEqualTo("HOST=db.example · PORT=1522 · SERVICE_NAME=adb_low");
        assertThat(ExternalAddress.connection("user/SECRET@host/service")).isEqualTo("[redacted]");
        assertThat(ExternalAddress.connection("host:1521/service?password=SECRET")).isEqualTo("[redacted]");
        assertThat(ExternalAddress.connection("host:1521/service")).isEqualTo("host:1521/service");
        assertThat(ExternalAddress.connection("my_tns_alias")).isEqualTo("my_tns_alias");
    }
    @Test void cacheIsSeparatedBySchemaTypeOwnerAndLogin(){
        var cache=new State();var calls=new AtomicInteger();java.util.function.Supplier<Detail> load=()->{calls.incrementAndGet();return detail("AVAILABLE");};
        cache.detail("APP",Kind.links,"APP","SAME",load);cache.detail("APP",Kind.links,"APP","SAME",load);
        cache.detail("APP",Kind.links,"PUBLIC","SAME",load);cache.detail("APP",Kind.tables,"APP","SAME",load);
        cache.detail("OTHER",Kind.links,"APP","SAME",load);new State().detail("APP",Kind.links,"APP","SAME",load);assertThat(calls).hasValue(5);
        cache.list("APP",Kind.links,true,()->catalog("AVAILABLE"));
        cache.detail("APP",Kind.links,"APP","SAME",load);cache.detail("APP",Kind.links,"PUBLIC","SAME",load);
        cache.detail("APP",Kind.tables,"APP","SAME",load);cache.detail("OTHER",Kind.links,"APP","SAME",load);assertThat(calls).hasValue(7);
    }
    @Test void failedRefreshDoesNotRestoreStaleDetailsAndTransientErrorsAreNotCached(){
        var cache=new State();var calls=new AtomicInteger();
        cache.detail("APP",Kind.links,"APP","L",()->detail("AVAILABLE"));
        assertThatThrownBy(()->cache.list("APP",Kind.links,true,()->{throw new IllegalStateException("read failed");})).hasMessage("read failed");
        for(int i=0;i<2;i++)cache.detail("APP",Kind.links,"APP","L",()->{calls.incrementAndGet();return detail("ERROR");});assertThat(calls).hasValue(2);
        for(int i=0;i<2;i++)cache.list("APP",Kind.links,false,()->{calls.incrementAndGet();return catalog("ACCESS_REQUIRED");});assertThat(calls).hasValue(3);
        for(int i=0;i<2;i++)cache.list("APP",Kind.tables,false,()->{calls.incrementAndGet();return catalog("ERROR");});assertThat(calls).hasValue(5);
    }
    @Test void cachedCollectionsCannotBeMutated(){
        assertThatThrownBy(()->catalog("AVAILABLE").items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->detail("AVAILABLE").fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->detail("AVAILABLE").sections().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->detail("AVAILABLE").sections().getFirst().items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void concurrentReadersOnlyLoadOnce() throws Exception {
        var cache=new State();var calls=new AtomicInteger();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var jobs=new ArrayList<java.util.concurrent.Future<Catalog>>();
            for(int i=0;i<16;i++)jobs.add(executor.submit(()->cache.list("APP",Kind.links,false,()->{calls.incrementAndGet();return catalog("AVAILABLE");})));
            var first=jobs.getFirst().get();for(var job:jobs)assertThat(job.get()).isSameAs(first);
        }
        assertThat(calls).hasValue(1);
    }
    @Test void realServiceCacheHitUsesNoConnectionAndRejectsOutOfScopeNames(){
        var source=new SessionDataSource();var service=new ExternalSourcesService(source,new ExternalSourcesRepository(new org.springframework.jdbc.core.JdbcTemplate(source)));
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var login=new PoolSession(pool,"LOW",()->{})){
            var state=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));login.initialize(state);
            var storedList=state.externalSources().list("APP",Kind.links,false,()->catalog("AVAILABLE"));
            var storedDetail=state.externalSources().detail("APP",Kind.links,"APP",entry.name(),()->detail("AVAILABLE"));
            assertThat(service.list(login,"APP",Kind.links,false)).isSameAs(storedList);
            assertThat(service.detail(login,"APP",Kind.links,"APP",entry.name())).isSameAs(storedDetail);
            assertThatThrownBy(()->service.detail(login,"OTHER",Kind.links,"APP",entry.name())).isInstanceOf(Failure.class);
            assertThatThrownBy(()->service.detail(login,"APP",Kind.links,"OTHER",entry.name())).isInstanceOf(Failure.class);
            assertThatThrownBy(()->service.detail(login,"APP",Kind.links,"APP","' OR 1=1")).isInstanceOf(Failure.class);
            assertThatThrownBy(()->service.detail(login,"APP",Kind.links,"APP","x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
            assertThat(pool.getHikariPoolMXBean()).isNull();assertThatThrownBy(source::getConnection).hasMessage("No authenticated database session");
        }
    }
    @Test void queriesAreBoundUseSeparateCatalogsAndIncludePublicLinks(){
        assertThat(ExternalSourcesRepository.listSql(Kind.links,true,false)).contains("SYS.USER_DB_LINKS","SYS.ALL_DB_LINKS WHERE OWNER = 'PUBLIC'","SESSION_USER").doesNotContain("WHERE OWNER = ?");
        assertThat(ExternalSourcesRepository.listSql(Kind.links,false,false)).contains("SYS.DBA_DB_LINKS WHERE OWNER = ?");
        assertThat(ExternalSourcesRepository.listSql(Kind.links,false,true)).contains("SYS.ALL_DB_LINKS WHERE OWNER = ?");
        assertThat(ExternalSourcesRepository.listSql(Kind.tables,false,true)).contains("SYS.ALL_EXTERNAL_TABLES WHERE OWNER = ?");
        assertThat(ExternalSourcesRepository.locationSql("locations",true,false)).contains("SYS.USER_EXTERNAL_LOCATIONS WHERE TABLE_NAME = ?");
        assertThat(ExternalSourcesRepository.locationSql("partitions",false,false)).contains("SYS.DBA_XTERNAL_LOC_PARTITIONS WHERE TABLE_OWNER = ? AND TABLE_NAME = ?");
        assertThat(ExternalSourcesRepository.locationSql("subpartitions",false,true)).contains("PARTITION_NAME, SUBPARTITION_NAME","SYS.ALL_XTERNAL_LOC_SUBPARTITIONS");
        assertThatThrownBy(()->ExternalSourcesRepository.locationSql("injection",true,false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void errorsContainOnlyCodesNotServerTextOrConnectionSecrets(){
        assertThat(ExternalSourcesRepository.error(new SQLException("SECRET credential","",942))).isEqualTo("ORA-00942");
        assertThat(ExternalSourcesRepository.status(new SQLException("SECRET","",1031))).isEqualTo("ACCESS_REQUIRED");
        assertThat(ExternalSourcesRepository.status(new SQLException("SECRET","",904))).isEqualTo("UNSUPPORTED");
        assertThat(ExternalSourcesRepository.status(new SQLException("timeout","",1013))).isEqualTo("ERROR");
    }
    @Test void implementationNeverReadsAccessParametersOrExecutesRemoteSql() throws Exception {
        String repository=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/ExternalSourcesRepository.java"));
        assertThat(repository).doesNotContain("ACCESS_PARAMETERS","jdbc.update(","jdbc.execute(","DBMS_CLOUD","SELECT * FROM \"+e.","equals(\"ADMIN\")");
        assertThat(repository).contains("ExternalAddress.location","LinkEndpoint.parse","WHERE 1=0","FETCH FIRST");
        String service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/ExternalSourcesService.java"));
        assertThat(service).contains("read.setReadOnly(true)","read.setTimeout(10)","finally{source.clear();}","source.bind(session.pool(),session.metadata().info().username())");
        String controller=Files.readString(Path.of("src/main/java/com/dbcompanion/controller/ExternalSourcesController.java"));
        assertThat(controller).contains("no-store").doesNotContain("@PostMapping","@DeleteMapping","@PutMapping");
    }
    @Test void fourLanguageRealTemplatesPreserveNavigationAndEscapeSchema(){
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source());
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder(){@Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext c,String b,Map<String,Object> p){return "";}});
        for(String lang:List.of("ko","en","zh-CN","ja")){
            var context=new Context(Locale.forLanguageTag(lang));context.setVariable("activePage","external");context.setVariable("info",new DatabaseInfo("APP","APP","LOW","DB"));
            context.setVariable("schemas",List.of("<script>"));context.setVariable("selectedSchema","<script>");context.setVariable("languageReturn","/db/external-sources");
            String html=engine.process("external-sources",context);
            assertThat(html).contains("data-external","&lt;script&gt;","DB Link","External Table","data-external-acl-mode","data-external-review","dba_role_privs","data-external-tab=\"catalogs\"","data-external-catalog-help","user_mounted_catalogs","data-catalog-csrf","data-catalog-mount disabled","data-catalog-preview","aria-labelledby=\"catalog-mount-title\"").doesNotContain("??external.","??catalog.","??catalogOps.","th:","data-schema=\"<script>");
            assertThat(html.split("value=\"/db/external-sources\"",-1)).hasSize(4);
        }
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/db/external-sources")).isEqualTo("/db/external-sources");
    }
}
