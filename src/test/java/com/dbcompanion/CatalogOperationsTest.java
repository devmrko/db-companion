package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.CatalogOperations.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogOperationsTest {
    private final MountedCatalogsRepository.Target api=new MountedCatalogsRepository.Target("C##CLOUD$SERVICE","DBMS_CATALOG");
    private Grid grid(String key,String value){return new Grid(List.of(key),List.of(Map.of(key,value)));}
    private Prepared prepared(Instant expires){return new Prepared(new Preview("token","CAT","APP","L","sql"),new Link("APP","L","hash"),api.sql(),expires);}
    @Test void registrationNamesAreNormalizedAndSystemCatalogIsReserved(){
        assertThat(CatalogOperations.catalogName("  sales_1 ")).isEqualTo("SALES_1");
        for(String name:List.of("LOCAL","local","1CAT","A.B","A'","카탈로그","","A".repeat(129)))assertThatThrownBy(()->CatalogOperations.catalogName(name)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CatalogOperations.catalogName(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CatalogOperations.identifier("Name.With spaces")).isEqualTo("Name.With spaces");
        assertThatThrownBy(()->CatalogOperations.identifier("bad\nname")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void ownLinksShadowPublicAndOtherPrivateOwnersAreNotUsable(){
        var own=new Link("APP","L","a");var pub=new Link("PUBLIC","L","b");
        assertThat(CatalogOperations.chooseLink(List.of(own,pub),"APP","APP","L")).isEqualTo(own);
        assertThat(CatalogOperations.chooseLink(List.of(pub),"APP","PUBLIC","L")).isEqualTo(pub);
        assertThatThrownBy(()->CatalogOperations.chooseLink(List.of(own,pub),"APP","PUBLIC","L")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CatalogOperations.chooseLink(List.of(new Link("OTHER","L","c")),"APP","OTHER","L")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CatalogOperations.chooseLink(List.of(),"APP","APP","L")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void confirmationsAreOneUseExpireAndRefreshInvalidatesThem(){
        var state=new State();var now=Instant.parse("2026-09-19T00:00:00Z");state.prepare(prepared(now.plusSeconds(300)));
        assertThatThrownBy(()->state.consume("wrong",now)).isInstanceOf(IllegalArgumentException.class);
        assertThat(state.consume("token",now).view().catalog()).isEqualTo("CAT");
        assertThatThrownBy(()->state.consume("token",now)).isInstanceOf(IllegalArgumentException.class);
        state.prepare(prepared(now));assertThatThrownBy(()->state.consume("token",now)).isInstanceOf(IllegalArgumentException.class);
        state.prepare(prepared(now.plusSeconds(300)));state.clear();assertThatThrownBy(()->state.consume("token",now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void concurrentConfirmationHasOneConsumer() throws Exception {
        var state=new State();state.prepare(prepared(Instant.now().plusSeconds(300)));var count=new AtomicInteger();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var tasks=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<8;i++)tasks.add(executor.submit(()->{try{state.consume("token",Instant.now());count.incrementAndGet();}catch(IllegalArgumentException ignored){}}));
            for(var task:tasks)task.get();
        }assertThat(count).hasValue(1);
    }
    @Test void browseCacheIsBoundedImmutableScopeSeparatedAndDoesNotStoreFailure(){
        var state=new State();var key=new Key("CAT",Level.schemas,null,null);var calls=new AtomicInteger();
        var value=state.grid(key,()->grid("SCHEMA_NAME","A"));
        assertThat(state.grid(key,()->{throw new IllegalStateException();})).isSameAs(value);
        assertThatThrownBy(()->value.rows().getFirst().put("x","y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->state.grid(new Key("ERROR",Level.schemas,null,null),()->{throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
        state.grid(new Key("ERROR",Level.schemas,null,null),()->{calls.incrementAndGet();return value;});assertThat(calls).hasValue(1);
        for(int i=0;i<24;i++)state.grid(new Key("CAT"+i,Level.schemas,null,null),()->value);
        state.grid(key,()->{calls.incrementAndGet();return value;});assertThat(calls).hasValue(2);
        state.clear();state.grid(key,()->{calls.incrementAndGet();return value;});assertThat(calls).hasValue(3);
    }
    @Test void listRefreshDiscardsBrowseCacheAndPendingConfirmation(){
        var state=new MountedCatalogs.State();var key=new Key("CAT",Level.schemas,null,null);state.operations().grid(key,()->grid("SCHEMA_NAME","old"));
        state.operations().prepare(prepared(Instant.now().plusSeconds(300)));state.list(true,()->new MountedCatalogs.Catalog(List.of(),"source","AVAILABLE","",new MountedCatalogs.Api("VISIBLE",List.of(),"source","")));
        assertThat(state.operations().grid(key,()->grid("SCHEMA_NAME","new")).contains("SCHEMA_NAME","new")).isTrue();
        assertThatThrownBy(()->state.operations().consume("token",Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void mountSqlBindsNamesAndPreviewEscapesSqlLiterals(){
        assertThat(CatalogOperationsRepository.mountSql(api)).isEqualTo("BEGIN \"C##CLOUD$SERVICE\".\"DBMS_CATALOG\".MOUNT_DB_LINK(catalog_name => ?, db_link => ?, enabled => TRUE); END;");
        assertThat(CatalogOperationsRepository.previewSql(api,"CAT","L'X")).contains("db_link => 'L''X'");
    }
    @Test void browseSqlIsFilteredBoundedAndOnlyUsesPublicAllowlistedFields(){
        var fields=CatalogOperationsRepository.fields(Level.columns,List.of("SCHEMA_NAME","TABLE_NAME","COLUMN_NAME","COLUMN_ID","DATA_TYPE","COMMENTS","CREDENTIAL","CONFIGURATION","METADATA","PASSWORD"));
        assertThat(fields).doesNotContain("CREDENTIAL","CONFIGURATION","METADATA","PASSWORD");
        assertThat(CatalogOperationsRepository.gridSql(api,Level.columns,fields)).contains(".GET_COLUMNS(catalog_name => ?, schema_name => ?, table_name => ?, result_limit => 5001)","ORDER BY \"COLUMN_ID\", \"COLUMN_NAME\" FETCH FIRST 5001 ROWS ONLY").doesNotContain("WHERE");
        assertThat(CatalogOperationsRepository.functionSql(api,Level.schemas)).doesNotContain("schema_name","table_name");
        assertThatThrownBy(()->CatalogOperationsRepository.fields(Level.columns,List.of("NAME"))).isInstanceOf(CatalogOperationsRepository.UnsupportedShape.class).hasMessage("GET_COLUMNS · NAME");
        assertThatThrownBy(()->CatalogOperationsRepository.gridSql(api,Level.schemas,List.of("SCHEMA_NAME","PASSWORD"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void columnApiPreservesRemoteCaseWithBoundQuotedIdentifiers(){
        assertThat(CatalogOperationsRepository.browseArguments("CAT",Level.schemas,null,null)).containsExactly("CAT");
        assertThat(CatalogOperationsRepository.browseArguments("CAT",Level.tables,"Mixed",null)).containsExactly("CAT","Mixed");
        for(String name:List.of("TABLE_A","lower_table","Mixed Name","a.b","a\"b","a'b","literal_%")){
            var args=CatalogOperationsRepository.browseArguments("CAT",Level.columns,"OWNER",name);
            assertThat(args).containsExactly("CAT","\"OWNER\"","\""+name.replace("\"","\"\"")+"\"");
        }
        assertThatThrownBy(()->CatalogOperations.apiIdentifier("bad\nname")).isInstanceOf(IllegalArgumentException.class);
        var raw=grid("TABLE_NAME","lower_table");assertThat(raw.contains("TABLE_NAME","lower_table")).isTrue();
        assertThat(raw.contains("TABLE_NAME","\"lower_table\"")).isFalse();
        assertThat(CatalogOperationsRepository.gridSql(api,Level.columns,List.of("COLUMN_NAME")))
            .contains("ORDER BY \"COLUMN_NAME\" FETCH FIRST").doesNotContain("lower_table");
    }
    @Test void scopedApisDoNotRequireParentNamesToBeRepeatedInEachResultRecord(){
        var tables=CatalogOperationsRepository.fields(Level.tables,List.of("TABLE_NAME","COMMENTS"));
        var columns=CatalogOperationsRepository.fields(Level.columns,List.of("COLUMN_NAME","DATA_TYPE","NULLABLE"));
        assertThat(CatalogOperationsRepository.gridSql(api,Level.tables,tables)).contains("schema_name => ?").doesNotContain("WHERE");
        assertThat(CatalogOperationsRepository.gridSql(api,Level.columns,columns)).contains("schema_name => ?, table_name => ?").doesNotContain("WHERE");
        var parentOnly=CatalogOperationsRepository.fields(Level.columns,List.of("TABLE_NAME","COLUMN_NAME"));
        assertThat(CatalogOperationsRepository.gridSql(api,Level.columns,parentOnly)).contains("table_name => ?").doesNotContain("WHERE", "\"SCHEMA_NAME\"");
    }
    @Test void nativeDescriptionFieldsAreSelectedWithoutReadingRawMetadata(){
        var schemas=CatalogOperationsRepository.fields(Level.schemas,List.of("SCHEMA_NAME","SCHEMA_DESCRIPTION","PROPERTIES","METADATA"));
        var tables=CatalogOperationsRepository.fields(Level.tables,List.of("TABLE_NAME","TABLE_TYPE","TABLE_DESCRIPTION","PROPERTIES","METADATA","QUERY_INFO"));
        assertThat(schemas).containsExactly("SCHEMA_NAME","SCHEMA_DESCRIPTION");
        assertThat(tables).containsExactly("TABLE_NAME","TABLE_TYPE","TABLE_DESCRIPTION");
        assertThat(CatalogOperationsRepository.gridSql(api,Level.tables,tables)).contains("\"TABLE_DESCRIPTION\"").doesNotContain("METADATA","PROPERTIES","QUERY_INFO");
        assertThat(CatalogOperationsRepository.fields(Level.columns,List.of("COLUMN_NAME","METADATA","PROPERTIES")))
            .containsExactly("COLUMN_NAME");
    }
    @Test void realServiceCacheHitHasNoConnectionAndRejectsUnknownParentsAndOtherOwner(){
        var source=new SessionDataSource();var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source);var mounted=new MountedCatalogsRepository(jdbc);
        var service=new CatalogOperationsService(source,new CatalogOperationsRepository(jdbc,mounted),mounted,new MountedCatalogsService(source,mounted));
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var session=new PoolSession(pool,"LOW",()->{})){
            session.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));
            var state=session.metadata().externalSources().mounted();state.list(false,()->new MountedCatalogs.Catalog(List.of(new MountedCatalogs.Entry("CAT","DBLINK","YES")),"source","AVAILABLE","",new MountedCatalogs.Api("VISIBLE",List.of(),"source","")));
            state.operations().grid(new Key("CAT",Level.schemas,null,null),()->grid("SCHEMA_NAME","S"));
            state.operations().grid(new Key("CAT",Level.tables,"S",null),()->grid("TABLE_NAME","T"));
            var columns=state.operations().grid(new Key("CAT",Level.columns,"S","T"),()->grid("COLUMN_NAME","ID"));
            assertThat(service.browse(session,"CAT",Level.columns,"S","T")).isSameAs(columns);
            session.metadata().selectSchema("OTHER");assertThat(service.browse(session,"CAT",Level.columns,"S","T")).isSameAs(columns);
            assertThatThrownBy(()->service.browse(session,"CAT",Level.columns,"evil","T")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.browse(session,"CAT",Level.columns,"S","evil")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.preview(session,"OTHER","L","CAT2")).isInstanceOf(ExternalSources.Failure.class);
            assertThatThrownBy(()->service.mount(session,"invented-token")).isInstanceOf(IllegalArgumentException.class);
            assertThat(pool.getHikariPoolMXBean()).isNull();
        }
    }
    @Test void registrationAndBrowsingNeverCreateLinksGrantOrReadBusinessRows() throws Exception {
        var text=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/CatalogOperationsRepository.java"));
        assertThat(text).doesNotContain("CREATE DATABASE LINK","GRANT ","PREFILL_CATALOG_CACHE","FLUSH_CATALOG_CACHE","GENERATE_TABLE_SELECT","OPEN_CURSOR","UNMOUNT(","DBMS_CLOUD");
        var service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/CatalogOperationsService.java"));
        assertThat(service).contains("source.bind(session.pool(),session.metadata().info().username())","finally{source.clear();}","consume(token,Instant.now())","attempted.set(true)");
    }
}
