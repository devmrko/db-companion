package com.dbcompanion;

import com.dbcompanion.model.AiSqlHistory.*;
import com.dbcompanion.repository.AiSqlHistoryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiSqlHistoryTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);
    private Query query(Source source, String id, String text, String actor, int page) { return new Query(source, TODAY.minusDays(6), TODAY, id, text, actor, page); }
    private Item item(List<String> key) { return new Item(UUID.randomUUID().toString(), "date", "123456789abcd", "APP", "kind", "1", "preview", key); }
    @Test void validatesSourcesRangesAndUnsupportedFilters() {
        var q = Query.parse("audit", "", "", "", "", "", "1", TODAY);
        assertThat(q.from()).isEqualTo(TODAY.minusDays(6)); assertThat(q.to()).isEqualTo(TODAY);
        for (String s : List.of("bad", "V$SQL", "audit'")) assertThatThrownBy(() -> Query.parse(s,"","","","","","1",TODAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query(Source.cache,TODAY.minusDays(31),TODAY,"","","",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query(Source.cache,TODAY,TODAY.minusDays(1),"","","",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(Source.audit,"123456789abcd","","",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(Source.awr,"","","APP",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(Source.cache,"","x".repeat(501),"",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(Source.cache,"bad","","",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(Source.cache,"","","x".repeat(129),1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(Source.cache,"","","",1001)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void allListsBindFiltersAndFetchOnlyTenPlusSentinel() {
        String input = "한글 %_' OR 1=1--";
        for (Source source : Source.values()) {
            var q = query(source, source == Source.audit ? "" : "123456789abcd", input, source == Source.awr ? "" : input, 2);
            var stmt = AiSqlHistoryRepository.listStatement(q, "AUDSYS.UNIFIED_AUDIT_TRAIL");
            assertThat(stmt.sql()).contains("OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY", "INSTR(UPPER(", "REGEXP_LIKE(")
                    .doesNotContain(input, "COUNT(*)", "SQL_BINDS", "LIKE '%");
            assertThat(stmt.args()).containsSubsequence("2026-09-15", "2026-09-22").endsWith(10).contains(input);
            assertThat(stmt.sql().chars().filter(c -> c == '?').count()).isEqualTo(stmt.args().size());
        }
    }
    @Test void awrUsesSnapshotEndBoundsAndDatabaseContainerKeysNotExecutionTimestamp() {
        var sql = AiSqlHistoryRepository.listStatement(query(Source.awr,"","","",1),"").sql();
        assertThat(sql).contains("sn.END_INTERVAL_TIME >=", "SUM(st.EXECUTIONS_DELTA)", "MIN(sn.BEGIN_INTERVAL_TIME)",
                "sn.DBID=st.DBID", "sn.INSTANCE_NUMBER=st.INSTANCE_NUMBER", "sn.SNAP_ID=st.SNAP_ID", "h.DBID=t.DBID", "h.SQL_ID=t.SQL_ID", "NVL(h.CON_DBID,-1)=NVL(t.CON_DBID,-1)", "NVL(h.CON_ID,-1)=NVL(t.CON_ID,-1)")
                .doesNotContain("EXECUTION_TIME", "PARSING_SCHEMA_NAME ACTOR");
    }
    @Test void cacheSkipsGenerateRegexForUnrelatedSqlWithoutTruncatingLongCalls() {
        for (var match : Match.values()) {
            var q = new Query(Source.cache,TODAY.minusDays(6),TODAY,"","","",1,match);
            var statement = AiSqlHistoryRepository.listStatement(q, "");
            assertThat(statement.sql().chars().filter(c -> c == '?').count()).isEqualTo(statement.args().size());
            String predicate = statement.sql().substring(statement.sql().indexOf("FROM SYS.V_$SQL"));
            assertThat(predicate).doesNotContain("SUBSTR", "s.SQL_TEXT", "FETCH FIRST", "DBMS_CLOUD_AI");
            if (match == Match.select_ai) {
                assertThat(predicate).doesNotContain("INSTR");
            } else {
                assertThat(predicate).contains("WHEN INSTR(UPPER(s.SQL_FULLTEXT), ?) = 0 THEN 0 WHEN REGEXP_LIKE(s.SQL_FULLTEXT, ?, 'i') THEN 1 ELSE 0 END = 1");
                assertThat(statement.args()).containsSubsequence("DBMS_CLOUD_AI", AiSqlHistoryRepository.GENERATE_PATTERN);
            }
        }
    }
    @Test void cancellationIsNotCachedAndRetriesOnlyWhenPageIsRequestedAgain() {
        var state = new State(); var calls = new AtomicInteger();
        var q = query(Source.cache,"","","",1);
        var first = state.page(q, () -> {
            calls.incrementAndGet(); return new Page(List.of(),1,false,Instant.now(),new Failure(1013,"cancelled"));
        });
        assertThat(first.failure().cancelled()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
        var next = state.page(q, () -> { calls.incrementAndGet(); return Page.of(List.of(),1); });
        assertThat(next.failure()).isNull();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(state.page(q, () -> { throw new AssertionError("Successful page should be cached"); })).isSameAs(next);
    }
    @Test void auditDoesNotInventSqlIdOrCollectBindsAndRejectsShadowView() {
        var q = query(Source.audit,"","","APP",1);
        var sql = AiSqlHistoryRepository.listStatement(q,"AUDSYS.UNIFIED_AUDIT_TRAIL").sql();
        assertThat(sql).contains("NULL SQL_IDENT", "a.EVENT_TIMESTAMP_UTC >=", "a.OBJECT_NAME='DBMS_CLOUD_AI'", "a.DBUSERNAME = ?", "a.RETURN_CODE")
                .doesNotContain("a.SQL_ID", "SQL_BINDS", "V_$SQL");
        assertThatThrownBy(() -> AiSqlHistoryRepository.listStatement(q,"APP.UNIFIED_AUDIT_TRAIL")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void detailUsesOnlyStoredCompositeKeysAndFullClobWithAmbiguityLimit() {
        for (Source source : Source.values()) {
            var keys = switch(source) {
                case cache -> List.of("123456789abcd","1","3","0123456789ABCDEF","2026-09-21/12:00:00");
                case awr -> List.of("1","123456789abcd","2","3");
                case audit -> List.of("1","2","3","4","5","2026-09-21 00:00:00.123456","EXEC-ID","9");
            };
            var stmt = AiSqlHistoryRepository.detailStatement(new Selection(query(source,"","","",1),item(keys)),"AUDSYS.UNIFIED_AUDIT_TRAIL");
            var expected = new java.util.ArrayList<Object>(keys); expected.add(AiSqlHistoryRepository.GENERATE_PATTERN);
            assertThat(stmt.args()).containsExactlyElementsOf(expected);
            assertThat(stmt.sql()).contains("FULL_SQL", "FETCH FIRST 2 ROWS ONLY").doesNotContain("SUBSTR", "SQL_BINDS");
            assertThat(stmt.sql().chars().filter(c -> c == '?').count()).isEqualTo(stmt.args().size());
        }
    }
    @Test void tenRowPageAndSessionCacheRefreshKeepSourcesIsolated() {
        var state = new State(); var count = new AtomicInteger();
        var items = IntStream.range(0,11).mapToObj(i -> item(List.of("key"))).toList();
        var q = query(Source.cache,"","","",1); var audit = query(Source.audit,"","","",1);
        var p = state.page(q, () -> { count.incrementAndGet(); return Page.of(items,1); });
        assertThat(p.items()).hasSize(10); assertThat(p.hasNext()).isTrue();
        state.page(q, () -> { throw new AssertionError("Unexpected second read"); });
        assertThat(state.selection(p.items().getFirst().id()).query()).isEqualTo(q);
        state.page(audit, () -> Page.of(List.of(item(List.of("audit"))),1));
        state.refresh(Source.audit);
        assertThat(state.selection(p.items().getFirst().id())).isNotNull();
        state.refresh(Source.cache); assertThat(state.selection(p.items().getFirst().id())).isNull();
        assertThat(count.get()).isEqualTo(1);
        assertThatThrownBy(() -> state.selection("fake")).isInstanceOf(IllegalArgumentException.class);
        assertThat(state.selection(UUID.randomUUID().toString())).isNull();
    }
    @Test void cursorDetailExposesCumulativeMetricsAndParsingIdentityNotInventedExecutionEvents() {
        var selected = new Selection(query(Source.cache,"","","",1), item(List.of("123456789abcd","0","75","address","loaded")));
        var sql = AiSqlHistoryRepository.detailStatement(selected, "").sql();
        assertThat(sql).contains("s.PARSING_USER_ID", "SYS.ALL_USERS", "PARSING_USER_NAME", "s.MODULE", "s.ACTION",
                "ROUND(s.ELAPSED_TIME/1000000,6) ELAPSED_SECONDS_TOTAL",
                "s.ELAPSED_TIME/NULLIF(s.EXECUTIONS,0)/1000000", "CPU_SECONDS_TOTAL", "PLSQL_SECONDS_TOTAL")
                .doesNotContain("LAST_EXECUTION_SECONDS", "EXECUTING_USER", "CREATED -", "LAST_ACTIVE_TIME -");
        var mapping = AiSqlHistoryRepository.mappingStatement(selected);
        assertThat(mapping.sql()).contains("FROM SYS.V_$MAPPED_SQL", "m.SQL_ID=? AND m.CON_ID=TO_NUMBER(?)", "MAPPED_SQL_FULLTEXT", "FETCH FIRST 11 ROWS ONLY")
                .doesNotContain("LAST_ACTIVE_TIME", "LIKE", "SUBSTR", "USER_CLOUD_AI", "SQL_BIND_CAPTURE");
        assertThat(mapping.args()).containsExactly("123456789abcd", "75");
        assertThatThrownBy(() -> AiSqlHistoryRepository.mappingStatement(new Selection(query(Source.audit,"","","",1),item(List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cachedFailuresAndPolicyStateOnlyReloadOnExplicitRefreshAndPagesAreBounded() {
        var state = new State(); var error = new Failure(942,"ORA-00942");
        var q = query(Source.cache,"","","",1);
        state.page(q, () -> new Page(List.of(),1,false,Instant.now(),error));
        assertThat(state.page(q, () -> { throw new AssertionError(); }).failure()).isEqualTo(error);
        var first = item(List.of("a")); state.page(query(Source.cache,"","one","",1), () -> Page.of(List.of(first),1));
        for (int n=0;n<13;n++) state.page(query(Source.cache,"","q"+n,"",1), () -> Page.of(List.of(),1));
        assertThat(state.selection(first.id())).isNull();
        state.policies(() -> new Policies(List.of(),false,Instant.now(),error));
        state.refresh(Source.awr);
        assertThat(state.policies(() -> { throw new AssertionError(); }).failure()).isEqualTo(error);
        state.refresh(Source.audit);
        assertThat(state.policies(() -> new Policies(List.of(),false,Instant.now(),null)).failure()).isNull();
    }
    @Test void codeIsReadOnlyUsesLoginPoolAndDoesNotQueryOnInitialViewOrAutoEnableAudit() throws Exception {
        String repo = Files.readString(Path.of("src/main/java/com/dbcompanion/repository/AiSqlHistoryRepository.java"));
        String service = Files.readString(Path.of("src/main/java/com/dbcompanion/service/AiSqlHistoryService.java"));
        String controller = Files.readString(Path.of("src/main/java/com/dbcompanion/controller/AiSqlHistoryController.java"));
        assertThat(repo).doesNotContain("jdbc.execute", "jdbc.update", "SQL_BINDS", "prepareCall", "registerOutParameter", "CREATE AUDIT", "GRANT ");
        assertThat(service).contains("read.setReadOnly(true)", "read.setTimeout(10)", "source.bind(session.pool()", "source.clear()", "&& !awrAllowed");
        assertThat(controller).contains("if (load && available)", "boolean refresh", "return \"redirect:\"").doesNotContain("PostMapping");
        assertThat(AiSqlHistoryRepository.POLICY_SQL).contains("LEFT JOIN", "e.ENABLED_OPTION", "e.SUCCESS, e.FAILURE", "p.AUDIT_CONDITION", "p.AUDIT_ONLY_TOPLEVEL", "FETCH FIRST 201 ROWS ONLY");
    }
}
