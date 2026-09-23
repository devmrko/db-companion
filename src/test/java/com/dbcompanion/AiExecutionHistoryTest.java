package com.dbcompanion;

import com.dbcompanion.model.AiExecutionHistory;
import com.dbcompanion.model.AiExecutionHistory.*;
import com.dbcompanion.repository.AiExecutionHistoryRepository;
import com.dbcompanion.service.AiExecutionHistoryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Production filter/SQL/paging rules only. No mocked database or authentication. */
class AiExecutionHistoryTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);
    private Query query(String profile, String question, int page) { return new Query(TODAY.minusDays(6), TODAY, profile, question, page); }

    @Test void defaultRangeIsSevenInclusiveKoreanCalendarDays() {
        var query = Query.parse("", "", "", "", "1", TODAY);
        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(query.to()).isEqualTo(TODAY);
        assertThat(AiExecutionHistoryRepository.listStatement(query).args()).containsExactly("2026-09-10T15:00:00+00:00", "2026-09-17T15:00:00+00:00", 0);
    }
    @Test void rejectsInvalidRangesLengthsAndPagesWithoutFallbackQueries() {
        assertThatThrownBy(() -> new Query(TODAY, TODAY.minusDays(1), "", "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query(TODAY.minusDays(31), TODAY, "", "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatNoException().isThrownBy(() -> new Query(TODAY.minusDays(30), TODAY, "", "", 1));
        assertThatThrownBy(() -> Query.parse("not-date", "", "", "", "1", TODAY)).isInstanceOf(java.time.DateTimeException.class);
        assertThatThrownBy(() -> Query.parse("", "", "", "", "1 OR 1=1", TODAY)).isInstanceOf(IllegalArgumentException.class);
        for (int page : new int[]{0, -1, 1001, Integer.MAX_VALUE}) assertThatThrownBy(() -> query("", "", page)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("x".repeat(129), "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("", "x".repeat(501), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(query("", "", 1000).offset()).isEqualTo(9990);
    }
    @Test void filtersAreBoundContainsSearchIncludingLiteralPercentUnderscoreAndQuotes() {
        String input = "x%' OR 1=1 --_한글";
        var statement = AiExecutionHistoryRepository.listStatement(query(input, input, 2));
        assertThat(statement.sql()).contains("INSTR(UPPER(PROFILE_NAME), UPPER(?))", "INSTR(UPPER(PROMPT), UPPER(?))", "OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY")
                .doesNotContain(input, "LIKE", "COUNT(*)", "PROMPT_RESPONSE");
        assertThat(statement.args()).containsExactly("2026-09-10T15:00:00+00:00", "2026-09-17T15:00:00+00:00", input, input, 10);
        assertThat(statement.sql().chars().filter(c -> c == '?').count()).isEqualTo(statement.args().size());
    }
    @Test void pageUsesEleventhRowOnlyAsNextSentinelAndDoesNotReturnIt() {
        List<Item> rows = IntStream.range(0, 11).mapToObj(i -> new Item("id" + i, "date", "P", "chat", "q")).toList();
        assertThat(Page.of(rows, 1).items()).hasSize(10); assertThat(Page.of(rows, 1).hasNext()).isTrue();
        assertThat(Page.of(rows.subList(0, 10), 2).hasNext()).isFalse();
        assertThat(Page.of(List.of(), 99).items()).isEmpty();
    }
    @Test void detailIsOneBoundIdAndKeepsFullClobs() {
        String sql = AiExecutionHistoryRepository.detailSql();
        assertThat(sql).contains("PROMPT, PROMPT_RESPONSE", "CONVERSATION_PROMPT_ID = ?", "FETCH FIRST 1 ROW ONLY").doesNotContain("SUBSTR", "DBA_");
        assertThat(AiExecutionHistory.requireId("ABCD_1234-5678")).isEqualTo("ABCD_1234-5678");
        for (String bad : List.of("", "x' OR 1=1", "a".repeat(37), "a\n")) assertThatThrownBy(() -> AiExecutionHistory.requireId(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void adminDoesNotBypassOwnerScopeAndStaleSchemaIsRejected() {
        AiExecutionHistoryService.requireScope("APP", "APP", "APP");
        assertThatThrownBy(() -> AiExecutionHistoryService.requireScope("ADMIN", "APP", "APP")).hasMessageContaining("owner");
        assertThatThrownBy(() -> AiExecutionHistoryService.requireScope("APP", "OTHER", "APP")).hasMessageContaining("changed");
    }
    @Test void readsReuseSessionPoolAndNeverCreateOrRunAi() throws Exception {
        String service = Files.readString(Path.of("src/main/java/com/dbcompanion/service/AiExecutionHistoryService.java"));
        assertThat(service).contains("read.setReadOnly(true)", "read.setTimeout(10)", "source.bind(session.pool()", "source.clear()", "synchronized (session)")
                .doesNotContain("accessibleSchemas", "new Hikari", ".login(");
        String repository = Files.readString(Path.of("src/main/java/com/dbcompanion/repository/AiExecutionHistoryRepository.java"));
        assertThat(repository).doesNotContain("jdbc.update", "jdbc.execute", "RUN_TEAM", "GENERATE(", "CREATE ", "GRANT ");
    }
}
