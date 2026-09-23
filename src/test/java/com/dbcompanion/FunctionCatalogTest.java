package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.service.FunctionCatalogService;
import com.dbcompanion.service.SqlObjectName;
import com.dbcompanion.repository.FunctionCatalogRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FunctionCatalogTest {
    @Test void referencePreservesQuotedSchemaObjectMemberAndRejectsEmptyIdentifiers(){
        String value=FunctionCatalog.reference("Mixed.Owner","P\"KG","Fn.명");
        assertThat(SqlObjectName.parts(value)).containsExactly("Mixed.Owner","P\"KG","Fn.명");
        assertThat(SqlObjectName.parts(FunctionCatalog.reference("APP","FUNC",null))).containsExactly("APP","FUNC");
        assertThatThrownBy(()->FunctionCatalog.reference("","F",null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void schemaMustStillMatchAndLanguageRouteRemainsLocal(){
        FunctionCatalogService.scope("APP","APP");
        assertThatThrownBy(()->FunctionCatalogService.scope("APP","OTHER")).isInstanceOf(FunctionCatalog.Failure.class);
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("/db/functions?name=FN")).isEqualTo("/db/functions?name=FN");
        assertThat(com.dbcompanion.controller.LanguageController.safeReturn("//example.test/db/functions")).isEqualTo("/login");
    }
    @Test void functionListsAreImmutableSessionScopedAndOnlyExplicitlyRefreshed(){
        var state=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));
        var calls=new AtomicInteger();
        java.util.function.Supplier<List<FunctionCatalog.Entry>> load=()->{calls.incrementAndGet();return List.of(new FunctionCatalog.Entry("FN",null,"FN","APP.FN"));};
        state.functions("APP",false,load);state.functions("APP",false,load);assertThat(calls).hasValue(1);
        state.functions("OTHER",false,load);assertThat(calls).hasValue(2);
        assertThatThrownBy(()->state.functions("APP",false,load).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->state.functions("APP",true,()->{throw new IllegalStateException("read error");})).hasMessage("read error");
        state.functions("APP",false,load);assertThat(calls).hasValue(3);
        var other=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP"));other.functions("APP",false,load);assertThat(calls).hasValue(4);
    }
    @Test void namedTypesAndPlsqlTypesKeepTheirOwnerAndPackage(){
        assertThat(FunctionCatalogRepository.type("PL/SQL RECORD","APP","PKG","ROW_T",null)).isEqualTo("APP.PKG.ROW_T");
        assertThat(FunctionCatalogRepository.type("NUMBER",null,null,null,"PLS_INTEGER")).isEqualTo("PLS_INTEGER");
        assertThat(FunctionCatalogRepository.type("VARCHAR2",null,null,null,null)).isEqualTo("VARCHAR2");
    }
    private DatabaseSession session(){return new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));}
    private List<FunctionCatalog.Detail> details(){
        var definition=new RoutineSource.Definition("APP","PKG","FN","PACKAGE",List.of(new RoutineSource.Section("PACKAGE","package pkg as function fn return number; end;")));
        return List.of(new FunctionCatalog.Detail(definition,List.of(new FunctionCatalog.ObjectState("PACKAGE","VALID","time")),List.of()));
    }
    @Test void detailsReuseNormalizedReferencesAndRemainSchemaAndLoginScoped(){
        var state=session();var calls=new AtomicInteger();
        java.util.function.Supplier<List<FunctionCatalog.Detail>> load=()->{calls.incrementAndGet();return details();};
        var first=state.functionDetails("APP",SqlObjectName.parts("pkg.fn"),load);
        assertThat(state.functionDetails("APP",SqlObjectName.parts("\"PKG\".\"FN\""),load)).isSameAs(first);assertThat(calls).hasValue(1);
        state.functionDetails("OTHER",SqlObjectName.parts("PKG.FN"),load);
        state.functionDetails("APP",SqlObjectName.parts("\"pkg\".\"fn\""),load);
        session().functionDetails("APP",SqlObjectName.parts("PKG.FN"),load);assertThat(calls).hasValue(4);
        assertThatThrownBy(first::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->first.getFirst().definition().sections().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->first.getFirst().objects().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->first.getFirst().arguments().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void refreshInvalidatesOnlyTheSelectedSchemaEvenWhenListReloadFails(){
        var state=session();var calls=new AtomicInteger();var reference=SqlObjectName.parts("F");
        java.util.function.Supplier<List<FunctionCatalog.Detail>> load=()->{calls.incrementAndGet();return details();};
        state.functionDetails("APP",reference,load);var other=state.functionDetails("OTHER",reference,load);
        state.functions("APP",false,List::of);state.functionDetails("APP",reference,load);assertThat(calls).hasValue(2);
        assertThatThrownBy(()->state.functions("APP",true,()->{throw new IllegalStateException("reload failed");})).hasMessage("reload failed");
        state.functionDetails("APP",reference,load);assertThat(calls).hasValue(3);
        assertThat(state.functionDetails("OTHER",reference,load)).isSameAs(other);assertThat(calls).hasValue(3);
        state.functions("APP",true,List::of);state.functionDetails("APP",reference,load);assertThat(calls).hasValue(4);
    }
    @Test void emptyDetailsCacheButFailedLoadsCanBeRetried(){
        var state=session();var reference=SqlObjectName.parts("F");var calls=new AtomicInteger();
        assertThatThrownBy(()->state.functionDetails("APP",reference,()->{throw new IllegalStateException("read failed");})).hasMessage("read failed");
        for(int i=0;i<2;i++)assertThat(state.functionDetails("APP",reference,()->{calls.incrementAndGet();return List.of();})).isEmpty();
        assertThat(calls).hasValue(1);
    }
    @Test void concurrentCacheReadsLoadOnce() throws Exception {
        var state=session();var calls=new AtomicInteger();var reference=SqlObjectName.parts("PKG.FN");
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var start=new java.util.concurrent.CountDownLatch(1);
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<List<FunctionCatalog.Detail>>>();
            for(int i=0;i<16;i++)tasks.add(executor.submit(()->{start.await();return state.functionDetails("APP",reference,()->{calls.incrementAndGet();return details();});}));
            start.countDown();var first=tasks.getFirst().get(2,java.util.concurrent.TimeUnit.SECONDS);
            for(var task:tasks)assertThat(task.get(2,java.util.concurrent.TimeUnit.SECONDS)).isSameAs(first);
        }
        assertThat(calls).hasValue(1);
    }
    @Test void cacheHitBypassesTheRealServiceConnectionAndTransactionPath(){
        var source=new com.dbcompanion.common.db.SessionDataSource();
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source);
        var service=new FunctionCatalogService(source,new FunctionCatalogRepository(jdbc),new com.dbcompanion.repository.RoutineSourceRepository(jdbc));
        // A real, deliberately unconfigured pool cannot open a DB connection. No mock DataSource or fake HTTP login.
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var login=new com.dbcompanion.common.db.PoolSession(pool,"LOW",()->{})){
            var state=session();login.initialize(state);
            var stored=state.functionDetails("APP",SqlObjectName.parts("PKG.FN"),this::details);
            assertThat(service.detail(login,"APP","pkg.fn")).isSameAs(stored);
            assertThat(service.detail(login,"APP","\"PKG\".\"FN\"")).isSameAs(stored);
            assertThat(pool.getHikariPoolMXBean()).isNull();
            assertThatThrownBy(()->service.detail(login,"OTHER","PKG.FN")).isInstanceOf(FunctionCatalog.Failure.class);
            assertThatThrownBy(source::getConnection).hasMessage("No authenticated database session");
        }
    }
    @Test void dictionaryQueriesAreScopedReadOnlyAndSourceIsLazy() throws Exception{
        var code=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dbcompanion/repository/FunctionCatalogRepository.java"));
        assertThat(code).contains("p.OWNER = ?","a.POSITION = 0","a.SUBPROGRAM_ID = p.SUBPROGRAM_ID","PACKAGE_NAME = ? AND OBJECT_NAME = ?","ORDER BY SUBPROGRAM_ID, SEQUENCE");
        assertThat(code).doesNotContain("IS_FUNCTION","DBA_","ALL_SOURCE","jdbc.execute(","jdbc.update(");
        var service=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/dbcompanion/service/FunctionCatalogService.java"));
        assertThat(service).contains("read.setReadOnly(true)","read.setTimeout(10)","finally{source.clear();}","routines.source(schema,name,2_000_000)");
    }
}
