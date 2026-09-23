package com.dbcompanion;

import com.dbcompanion.model.AiSqlHistory.*;
import com.dbcompanion.repository.AiAssistantRepository;
import com.dbcompanion.repository.AiSqlHistoryRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiSqlGenerateHistoryTest {
    private static final LocalDate TODAY = LocalDate.of(2026,9,21);
    private static Query query(Source source, Match match) {
        return new Query(source,TODAY.minusDays(6),TODAY,"","","",2,match);
    }
    // Java spelling of the two POSIX classes; this is a pattern contract, not an Oracle integration test.
    private static Pattern pattern() {
        return Pattern.compile(AiSqlHistoryRepository.GENERATE_PATTERN.replace("[:space:]", "\\s")
                .replace("[:alnum:]", "A-Za-z0-9"), Pattern.CASE_INSENSITIVE);
    }
    @Test void matchesActualAppCallQuotedOwnersWhitespaceAndFullLengthText() {
        var pattern = pattern();
        for (var sql : List.of(AiAssistantRepository.generateSql("C##CLOUD$SERVICE"),
                "select DBMS_CLOUD_AI.GENERATE(prompt=>:1) from dual",
                "BEGIN :1 := admin . \"DBMS_CLOUD_AI\" . \"GENERATE\"\n (prompt=>:2); END;",
                "BEGIN " + " ".repeat(2000) + ":1 := dbms_cloud_ai\n.\ngenerate (:2); END;")) {
            assertThat(pattern.matcher(sql).find()).as(sql.substring(0,Math.min(60,sql.length()))).isTrue();
        }
    }
    @Test void rejectsSimilarPackagesAndProceduresAndDoesNotFindItsOwnQuery() {
        var pattern=pattern();
        for (String sql:List.of("select MY_DBMS_CLOUD_AI.GENERATE(:1) from dual",
                "begin DBMS_CLOUD_AI.GENERATE_SQL(:1); end;", "begin DBMS_CLOUD_AI_AGENT.GENERATE(:1); end;",
                "select DBMS_CLOUD_AI.GENERATE_REPORT(:1) from dual", "begin DBMS_CLOUD_AI.SET_PROFILE(:1); end;"))
            assertThat(pattern.matcher(sql).find()).as(sql).isFalse();
        for(var source:Source.values()) for(var match:Match.values()) {
            var stmt=AiSqlHistoryRepository.listStatement(query(source,match),"AUDSYS.UNIFIED_AUDIT_TRAIL");
            assertThat(pattern.matcher(stmt.sql()).find()).isFalse();
            assertThat(stmt.sql()).doesNotContain(AiSqlHistoryRepository.GENERATE_PATTERN);
        }
    }
    @Test void allListBranchesUseFullTextAndCorrectOrderedBinds() {
        for(var source:Source.values()) for(var match:Match.values()) {
            var stmt=AiSqlHistoryRepository.listStatement(query(source,match),"AUDSYS.UNIFIED_AUDIT_TRAIL");
            var expected=new ArrayList<Object>();
            if(source!=Source.awr && match==Match.all)expected.add(AiSqlHistoryRepository.GENERATE_PATTERN);
            expected.add("2026-09-15");expected.add("2026-09-22");
            if(source==Source.awr && match==Match.all)expected.add(AiSqlHistoryRepository.GENERATE_PATTERN);
            if(match!=Match.select_ai)expected.add(AiSqlHistoryRepository.GENERATE_PATTERN);
            expected.add(10);
            assertThat(stmt.args()).containsExactlyElementsOf(expected);
            assertThat(stmt.sql().chars().filter(c->c=='?').count()).isEqualTo(expected.size());
            if(source==Source.cache)assertThat(stmt.sql()).contains("REGEXP_LIKE(s.SQL_FULLTEXT").doesNotContain("REGEXP_LIKE(s.SQL_TEXT");
            if(match==Match.generate)assertThat(stmt.sql()).doesNotContain("select[[:space:]]+ai", "a.OBJECT_NAME='DBMS_CLOUD_AI'");
            if(source==Source.audit && match==Match.all)assertThat(stmt.sql()).contains("a.OBJECT_NAME='DBMS_CLOUD_AI'");
        }
    }
    @Test void detailRetainsOriginalMatchAndAllCompositeKeys() {
        for(var source:Source.values()) for(var match:Match.values()) {
            var keys=switch(source){case cache->List.of("sql","0","3","address","loaded");case awr->List.of("1","sql","2","3");case audit->List.of("1","2","3","4","5","date","7","8");};
            var row=new Item(UUID.randomUUID().toString(),"","","","","","",keys);
            var stmt=AiSqlHistoryRepository.detailStatement(new Selection(query(source,match),row),"AUDSYS.UNIFIED_AUDIT_TRAIL");
            var expected=new ArrayList<Object>(keys);if(match!=Match.select_ai)expected.add(AiSqlHistoryRepository.GENERATE_PATTERN);
            assertThat(stmt.args()).containsExactlyElementsOf(expected);
            assertThat(stmt.sql().chars().filter(c->c=='?').count()).isEqualTo(expected.size());
            if(match==Match.generate)assertThat(stmt.sql()).doesNotContain("select[[:space:]]+ai", "a.OBJECT_NAME='DBMS_CLOUD_AI'");
        }
    }
    @Test void matchIsValidatedAndIsPartOfSessionCacheKey() {
        assertThat(Query.parse("cache","","","","","","1",TODAY).match()).isEqualTo(Match.all);
        assertThatThrownBy(()->Query.parse("cache","","","","","","1","generate' OR 1=1",TODAY)).isInstanceOf(IllegalArgumentException.class);
        var state=new State();
        for(var match:Match.values()) {
            var q=query(Source.cache,match);
            var p=state.page(q,()->Page.of(List.of(new Item(UUID.randomUUID().toString(),"","","",match.name(),"","",List.of())),2));
            assertThat(p.items().getFirst().kind()).isEqualTo(match.name());
            assertThat(state.page(q,()->{throw new AssertionError("Unexpected reread");})).isSameAs(p);
        }
    }
}
