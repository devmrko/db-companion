package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.MountedCatalogs.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.MountedCatalogsService;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MountedCatalogsTest {
    private final Entry entry=new Entry("ICEBERG_CAT","ICEBERG","YES");
    private Catalog catalog(String status,String api){return new Catalog(List.of(entry),"SYS.USER_MOUNTED_CATALOGS",status,"",new Api(api,List.of("GET_TABLES"),"SYS.ALL_PROCEDURES",""));}
    private Detail detail(){return new Detail(entry,List.of(new Field("CATALOG_ID","3")),"SYS.USER_MOUNTED_CATALOGS");}
    @Test void listCapabilitiesAndDetailsAreCachedUntilExplicitRefresh(){
        var state=new State();var calls=new AtomicInteger();
        for(int i=0;i<2;i++)state.list(false,()->{calls.incrementAndGet();return catalog("AVAILABLE","VISIBLE");});
        for(int i=0;i<2;i++)state.detail(entry.name(),()->{calls.incrementAndGet();return detail();});
        assertThat(calls).hasValue(2);
        state.list(true,()->{calls.incrementAndGet();return catalog("AVAILABLE","VISIBLE");});
        state.detail(entry.name(),()->{calls.incrementAndGet();return detail();});assertThat(calls).hasValue(4);
    }
    @Test void accessUnknownIsStableButTransientApiAndViewFailuresAreNotCached(){
        var state=new State();var calls=new AtomicInteger();
        for(int i=0;i<2;i++)state.list(false,()->{calls.incrementAndGet();return catalog("ACCESS_REQUIRED","NOT_VISIBLE");});assertThat(calls).hasValue(1);
        state.list(true,()->catalog("ERROR","VISIBLE"));
        for(int i=0;i<2;i++)state.list(false,()->{calls.incrementAndGet();return catalog("AVAILABLE","ERROR");});assertThat(calls).hasValue(3);
    }
    @Test void failedRefreshDiscardsBothOldListAndDetails(){
        var state=new State();state.list(false,()->catalog("AVAILABLE","VISIBLE"));state.detail(entry.name(),this::detail);
        assertThatThrownBy(()->state.list(true,()->{throw new IllegalStateException("test failure");})).hasMessage("test failure");
        var calls=new AtomicInteger();state.list(false,()->{calls.incrementAndGet();return catalog("AVAILABLE","VISIBLE");});
        state.detail(entry.name(),()->{calls.incrementAndGet();return detail();});assertThat(calls).hasValue(2);
    }
    @Test void concurrentReadersShareOneLoadAndRecordsAreImmutable() throws Exception {
        var state=new State();var calls=new AtomicInteger();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var futures=new ArrayList<java.util.concurrent.Future<Catalog>>();
            for(int i=0;i<12;i++)futures.add(executor.submit(()->state.list(false,()->{calls.incrementAndGet();return catalog("AVAILABLE","VISIBLE");})));
            for(var future:futures)assertThat(future.get().items()).containsExactly(entry);
        }
        assertThat(calls).hasValue(1);
        assertThatThrownBy(()->catalog("AVAILABLE","VISIBLE").items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->catalog("AVAILABLE","VISIBLE").api().methods().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->detail().fields().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void sqlSelectsOnlyRecognizedColumnsAndBindsName(){
        var columns=new AgentViewColumns(List.of("CATALOG_NAME","CATALOG_TYPE","IS_ENABLED","ENDPOINT","CREDENTIAL_NAME","CONFIGURATION","CREDENTIAL_MAP","PASSWORD","METADATA","TOKEN"));
        var shape=new MountedCatalogsRepository.Shape(new MountedCatalogsRepository.Target("C##CLOUD$SERVICE","USER_MOUNTED_CATALOGS"),columns);
        assertThat(MountedCatalogsRepository.listSql(shape)).contains("ORDER BY \"CATALOG_NAME\" FETCH FIRST 5001 ROWS ONLY").doesNotContain("ENDPOINT","CREDENTIAL");
        assertThat(MountedCatalogsRepository.detailSql(shape)).contains("\"ENDPOINT\", \"CREDENTIAL_NAME\"","WHERE \"CATALOG_NAME\" = ? FETCH FIRST 2 ROWS ONLY")
            .doesNotContain("PASSWORD","METADATA","CONFIGURATION","CREDENTIAL_MAP","TOKEN");
        assertThat(MountedCatalogsRepository.detailFields(new AgentViewColumns(List.of("name")))).isEmpty();
    }
    @Test void databaseIdentifiersAreQuotedNotConcatenatedAsExecutableSql(){
        assertThat(new MountedCatalogsRepository.Target("A\"B","V").sql()).isEqualTo("\"A\"\"B\".\"V\"");
        assertThatThrownBy(()->new MountedCatalogsRepository.Target("SYS","x\0")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new MountedCatalogsRepository.Target("SYS","x".repeat(129))).isInstanceOf(RuntimeException.class);
    }
    @Test void endpointAndCredentialContainersAreRedactedBeforeCaching(){
        assertThat(MountedCatalogsRepository.displayValue("ENDPOINT","https://user:SECRET@host:443/p/SECRET?token=SECRET")).isEqualTo("https://host:443/[redacted]");
        assertThat(MountedCatalogsRepository.displayValue("ENDPOINT","user/SECRET@host")).isEqualTo("[redacted]");
        assertThat(MountedCatalogsRepository.displayValue("ENDPOINT",null)).isEmpty();
        assertThat(MountedCatalogsRepository.displayValue("CATALOG_CREDENTIAL","{\"token\":\"SECRET\"}")).isEqualTo("[redacted]");
        assertThat(MountedCatalogsRepository.displayValue("CREDENTIAL_NAME","APP.MY_CRED")).isEqualTo("APP.MY_CRED");
        assertThatThrownBy(()->MountedCatalogsRepository.displayValue("PASSWORD","SECRET")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cacheHitIsIndependentOfSelectedSchemaAndNeverAcquiresConnection(){
        var source=new SessionDataSource();var service=new MountedCatalogsService(source,new MountedCatalogsRepository(new org.springframework.jdbc.core.JdbcTemplate(source)));
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var login=new PoolSession(pool,"LOW",()->{})){
            var session=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));login.initialize(session);
            var list=session.externalSources().mounted().list(false,()->catalog("AVAILABLE","VISIBLE"));
            var detail=session.externalSources().mounted().detail(entry.name(),this::detail);
            assertThat(service.list(login,false)).isSameAs(list);assertThat(service.detail(login,entry.name())).isSameAs(detail);
            session.selectSchema("OTHER");assertThat(service.list(login,false)).isSameAs(list);
            assertThat(service.detail(login,entry.name())).isSameAs(detail);
            assertThatThrownBy(()->service.detail(login,"' OR 1=1")).isInstanceOf(ExternalSources.Failure.class);
            assertThatThrownBy(()->service.detail(login,"x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
            assertThat(pool.getHikariPoolMXBean()).isNull();assertThatThrownBy(source::getConnection).hasMessage("No authenticated database session");
            assertThat(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP")).externalSources().mounted()).isNotSameAs(session.externalSources().mounted());
        }
    }
    @Test void unconfirmedViewAccessIsNotReportedAsNotInstalled(){
        for(int code:List.of(942,1031))assertThat(MountedCatalogsRepository.status(new SQLException("SECRET","",code))).isEqualTo("ACCESS_REQUIRED");
        assertThat(MountedCatalogsRepository.status(new SQLException("SECRET","",904))).isEqualTo("UNSUPPORTED");
        assertThat(MountedCatalogsRepository.status(new SQLException("SECRET","",1013))).isEqualTo("ERROR");
    }
    @Test void implementationNeverInvokesRemotePackagesOrQueriesRawConfiguration() throws Exception {
        String repository=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/MountedCatalogsRepository.java"));
        assertThat(repository).doesNotContain("jdbc.update(","jdbc.execute(","DBMS_CATALOG.GET_","DBMS_CATALOG.MOUNT_", "\"CONFIGURATION\"","\"METADATA\"","\"PASSWORD\"")
            .contains("OWNER = 'PUBLIC' AND SYNONYM_NAME = ?","WHERE 1=0","if(r.getString(3)!=null)throw");
        String service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/MountedCatalogsService.java"));
        assertThat(service).contains("read.setReadOnly(true)","read.setTimeout(10)","finally{source.clear();}","source.bind(session.pool(),session.metadata().info().username())");
    }
    @Test void catalogTabReturnPathsAreAllowlisted(){
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/db/external-sources?tab=catalogs&aclView=schema")).isEqualTo("/db/external-sources?tab=catalogs&aclView=schema");
        assertThat(com.dbcompanion.controller.ExternalSourcesController.safeReturn("/db/external-sources?tab=catalogs&aclView=schema&url=//evil")).isFalse();
    }
}
