package com.dbcompanion;

import com.dbcompanion.model.AiAgentExecution.*;
import com.dbcompanion.model.DatabaseInfo;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

class AiAgentExecutionTemplateTest {
    private Context context(boolean runPage) {
        var c = new Context(java.util.Locale.KOREAN);
        c.setVariable("info", new DatabaseInfo("APP", "APP", "LOW", "DB"));
        c.setVariable("schemas", List.of("APP")); c.setVariable("selectedSchema", "APP");
        c.setVariable("activePage", "executions"); c.setVariable("executionReturn", "/ai-executions/agents");
        c.setVariable("runPage", runPage); c.setVariable("runId", runPage ? "run-1" : "");
        c.setVariable("from", "2026-09-11"); c.setVariable("to", "2026-09-17");
        c.setVariable("team", "T<script>"); c.setVariable("state", "");
        c.setVariable("query", new Query(LocalDate.of(2026,9,11), LocalDate.of(2026,9,17), "T<script>", "", 2));
        return c;
    }
    private String render(Context context) {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateEngineMessageSource(com.dbcompanion.common.i18n.UiMessages.source()); engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext context, String base, java.util.Map<String,Object> parameters) { return ""; }
        });
        return engine.process("ai-agent-executions", context);
    }
    @Test void listRendersThreeKindsEscapedFiltersPaginationAndNoTaskBodies() {
        var c = context(false);
        c.setVariable("runs", new Page<>(List.of(new Run("run-1", "<script>team</script>", "SUCCEEDED", "start", "end", "1초")
                .withPreview(InputPreview.of("<script>query</script>\" 한글", false))), 2, true));
        assertThat(render(c)).contains("Agent 실행", "저장된 대화", "Select AI SQL", "구분 기준", "TEAM_EXEC_ID", "TASK_ORDER", "여러 탭", "&lt;script&gt;team", "page=3", "name=\"returnTo\" value=\"/ai-executions/agents\"", "runId=run-1", "완료")
                .contains("질의", "질의 표시 기준", "&lt;script&gt;query&lt;/script&gt;&quot; 한글", "app-run-query", "첫 Task 입력")
                .doesNotContain("th:", "<script>team", "<script>query", "조회된 Agent 실행이 없습니다.", "data-agent-task=", "taskPage=");
    }
    @Test void runShowsRepeatedTasksAsSeparateOrderedRowsAndOnlyLoadsChosenDetails() {
        var c = context(true);
        c.setVariable("detail", new RunDetail(new Run("run-1", "TEAM", "RUNNING", "start", null, "—"),
                new Page<>(List.of(new Task(1,"TASK","AGENT","SUCCEEDED","start","end"), new Task(2,"TASK","AGENT","FAILED","start","end")), 2, true)));
        assertThat(render(c)).contains("← 실행 목록", "실행 중", "data-agent-task=\"1\"", "data-agent-task=\"2\"", "taskPage=3", "taskPage=1", "data-agent-input", "data-agent-result", "대화 조회", "다른 Task·실행의 메시지도 포함", "data-agent-prompt-detail hidden")
                .doesNotContain("name=\"team\"", "th:", "<textarea", "재실행");
    }
    @Test void missingAndErrorStatesNeverClaimEmptySuccess() {
        var c = context(false); c.setVariable("runs", new Page<>(List.of(),1,false));
        assertThat(render(c)).contains("조회된 Agent 실행이 없습니다.");
        c.setVariable("runs", null); c.setVariable("loadError", "ORA-00942: <history>");
        assertThat(render(c)).contains("ORA-00942: &lt;history&gt;").doesNotContain("조회된 Agent 실행이 없습니다.");
    }
    @Test void invalidInputsStillRenderRecoverableErrorPage() {
        var c = context(true); c.setVariable("query", null); c.setVariable("loadError", "입력 확인");
        assertThat(render(c)).contains("입력 확인", "← 실행 목록").doesNotContain("th:");
    }
}
