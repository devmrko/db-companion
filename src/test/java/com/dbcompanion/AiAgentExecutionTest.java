package com.dbcompanion;

import com.dbcompanion.model.AiAgentExecution;
import com.dbcompanion.model.AiAgentExecution.*;
import com.dbcompanion.repository.AgentViewColumns;
import com.dbcompanion.repository.AiAgentExecutionRepository;
import com.dbcompanion.service.AiExecutionHistoryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiAgentExecutionTest {
    private final AgentViewColumns columns = new AgentViewColumns(List.of("TEAM_EXEC_ID", "TEAM_NAME", "TASK_ORDER", "TASK_NAME", "AGENT_NAME", "STATE", "START_DATE", "END_DATE", "INPUT", "RESULT", "CONVERSATION_PARAMS"));
    @Test void rangeStatePageAndKeysAreBounded() {
        var query = Query.parse("", "", " T ", "", "", LocalDate.of(2026, 9, 17));
        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 9, 11)); assertThat(query.team()).isEqualTo("T");
        assertThatThrownBy(() -> Query.parse("2026-08-01", "2026-09-17", "", "", "1", LocalDate.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query(query.from(), query.to(), "", "bad", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiAgentExecution.requireRunId("x' OR 1=1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiAgentExecution.requireRunId("x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiAgentExecution.requirePage(1001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiAgentExecution.requireOrder(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiAgentExecution.requireOrder(9007199254740992L)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void teamListBindsFiltersAndDoesNotReadTaskOrClob() {
        var q = Query.parse("2026-09-01", "2026-09-17", "T_'%", "FAILED", "2", LocalDate.now());
        var sql = AiAgentExecutionRepository.listStatement(q, columns);
        assertThat(sql.sql()).contains("USER_AI_AGENT_TEAM_HISTORY", "INSTR(", "FETCH NEXT 11", "OFFSET ?")
                .doesNotContain("T_'%", "USER_AI_AGENT_TASK_HISTORY", "CONVERSATION", "COUNT(", "INPUT", "RESULT");
        assertThat(sql.args()).containsExactly("2026-09-01", "2026-09-18", "T_'%", "FAILED", 10);
    }
    @Test void tasksUseExecutionAndOrderNotNamesOrLatestGrouping() {
        var sql = AiAgentExecutionRepository.tasksStatement("run-1", 2, columns);
        assertThat(sql.sql()).contains("WHERE \"TEAM_EXEC_ID\" = ?", "ORDER BY \"TASK_ORDER\"", "FETCH NEXT 11")
                .doesNotContain("ROW_NUMBER", "GROUP BY", "INPUT", "RESULT");
        assertThat(sql.args()).containsExactly("run-1", 10);
        var detail = AiAgentExecutionRepository.taskStatement("run-1", 4, columns, true);
        assertThat(detail.sql()).contains("\"TASK_ORDER\" = ?", "FETCH FIRST 2", "\"INPUT\"", "\"RESULT\"");
        assertThat(detail.args()).containsExactly("run-1", 4L);
        var link = AiAgentExecutionRepository.taskStatement("run-1", 4, columns, false);
        assertThat(link.sql()).doesNotContain("\"INPUT\"", "\"RESULT\"");
    }
    @Test void documentedColumnVariationsAreResolvedFromMetadataOnly() {
        for (String name : List.of("CONVERSATION_PARAMS", "CONVERSATION_PARAM", "COVERSATION_PARAM")) {
            assertThat(AiAgentExecutionRepository.conversationProjection(new AgentViewColumns(List.of(name))))
                    .contains("JSON_VALUE(\"" + name + "\"", "NULL ON ERROR");
        }
        assertThat(AiAgentExecutionRepository.conversationProjection(new AgentViewColumns(List.of("INPUT")))).isEqualTo("NULL");
        assertThatThrownBy(() -> AiAgentExecutionRepository.tasksStatement("run", 1, new AgentViewColumns(List.of("TASK_NAME"))))
                .hasMessageContaining("TASK_ORDER");
    }
    @Test void firstInputPreviewOnlyReadsVisibleIdsAndDoesNotSelectAnArbitraryDuplicate() {
        var sql = AiAgentExecutionRepository.previewsStatement(List.of("run-1", "run-2"), columns);
        assertThat(sql.sql()).contains("DBMS_LOB.SUBSTR(INPUT_VALUE, 501, 1)", "WHERE \"TEAM_EXEC_ID\" IN (?,?)",
                "ORDER BY \"TASK_ORDER\" NULLS FIRST", "PARTITION BY \"TEAM_EXEC_ID\", \"TASK_ORDER\"", "OCCURRENCES = 1", "POSITION_IN_RUN = 1")
                .doesNotContain("run-1", "run-2", "RESULT", "PROMPT_RESPONSE");
        assertThat(sql.args()).containsExactly("run-1", "run-2");
        assertThatThrownBy(() -> AiAgentExecutionRepository.previewsStatement(List.of(), columns)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiAgentExecutionRepository.previewsStatement(java.util.Collections.nCopies(11,"r"), columns)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiAgentExecutionRepository.previewsStatement(List.of("x'"), columns)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void previewDistinguishesMissingAmbiguousAndTruncatedUnicode() {
        assertThat(InputPreview.of(null, false).getDisplay()).isEqualTo("—");
        assertThat(InputPreview.of("", false).getDisplay()).isEqualTo("—");
        assertThat(InputPreview.of("not used", true).getDisplay()).isEqualTo("확인 필요");
        String text = "😀".repeat(501);
        var preview = InputPreview.of(text, false);
        assertThat(preview.text()).isEqualTo("😀".repeat(500));
        assertThat(preview.clipped()).isTrue(); assertThat(preview.getTitle()).contains("앞 500자", "Task 상세");
        assertThat(InputPreview.of("한글".repeat(250), false).clipped()).isFalse();
    }
    @Test void linkedConversationDoesNotPretendEveryMessageIsExclusiveToTask() {
        var sql = AiAgentExecutionRepository.conversationsStatement("conversation'", 3);
        assertThat(sql.sql()).contains("CONVERSATION_ID = ?", "ORDER BY CREATED, CONVERSATION_PROMPT_ID", "DBMS_LOB.SUBSTR(PROMPT, 500, 1)")
                .doesNotContain("conversation'", "PROMPT_RESPONSE", "START_DATE", "PROMPT_ACTION =");
        assertThat(sql.args()).containsExactly("conversation'", 20);
    }
    @Test void pagesPreserveRepeatedTasksAndStateIsNotInvented() {
        var tasks = java.util.stream.LongStream.range(0, 11).mapToObj(n -> new Task(n, "same", "agent", "SUCCEEDED", "time", "time")).toList();
        var page = Page.of(tasks, 1);
        assertThat(page.items()).hasSize(10); assertThat(page.hasNext()).isTrue();
        assertThat(page.items()).extracting(Task::order).containsExactly(0L,1L,2L,3L,4L,5L,6L,7L,8L,9L);
        assertThat(AiAgentExecution.stateLabel("NEW_DB_STATE")).isEqualTo("NEW_DB_STATE");
        assertThat(AiAgentExecution.duration(null)).isEqualTo("—");
        assertThat(AiAgentExecutionRepository.unique(List.<String>of())).isNull();
        assertThat(AiAgentExecutionRepository.unique(List.of("one"))).isEqualTo("one");
        assertThatThrownBy(() -> AiAgentExecutionRepository.unique(List.of("one", "two")))
                .isInstanceOf(AiAgentExecutionRepository.AmbiguousTask.class);
    }
    @Test void ownScopeAndPoolContractRemainReadOnly() throws Exception {
        AiExecutionHistoryService.requireScope("APP", "APP", "APP");
        assertThatThrownBy(() -> AiExecutionHistoryService.requireScope("ADMIN", "APP", "APP")).isInstanceOf(RuntimeException.class);
        String source = Files.readString(Path.of("src/main/java/com/dbcompanion/service/AiAgentExecutionService.java"));
        assertThat(source).contains("source.bind(session.pool()", "read.setReadOnly(true)", "read.setTimeout(10)", "source.clear()", "requireScope")
                .doesNotContain("new Hikari", "accessibleSchemas");
        String repository = Files.readString(Path.of("src/main/java/com/dbcompanion/repository/AiAgentExecutionRepository.java"));
        assertThat(repository).doesNotContain("jdbc.update", "jdbc.execute", "RUN_TEAM(", "GENERATE(", "GRANT ");
    }
}
