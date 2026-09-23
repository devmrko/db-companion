package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.OracleErrorDetails;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.AiAgentExecution.Query;
import com.dbcompanion.model.AiExecutionHistory;
import com.dbcompanion.repository.AiAgentExecutionRepository;
import com.dbcompanion.service.AiAgentExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AiAgentExecutionController {
    public static final String PATH = "/ai-executions/agents";
    private final AiAgentExecutionService service;
    public AiAgentExecutionController(AiAgentExecutionService service) { this.service = service; }

    @GetMapping({PATH, PATH + "/run"})
    public String page(@RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
                       @RequestParam(defaultValue = "") String team, @RequestParam(defaultValue = "") String state,
                       @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "1") String taskPage,
                       @RequestParam(defaultValue = "") String schema, @RequestParam(defaultValue = "") String runId,
                       HttpServletRequest request, HttpServletResponse response, Model model) {
        var session = session(request);
        if (session == null) return "redirect:/login";
        response.setHeader("Cache-Control", "no-store");
        boolean runPage = request.getRequestURI().endsWith("/run");
        synchronized (session) {
            var metadata = session.metadata();
            model.addAttribute("info", metadata.info()); model.addAttribute("schemas", metadata.schemas());
            model.addAttribute("selectedSchema", metadata.selectedSchema()); model.addAttribute("activePage", "executions");
            model.addAttribute("executionReturn", PATH); model.addAttribute("runPage", runPage);
            model.addAttribute("runId", runId);
            model.addAttribute("from", from); model.addAttribute("to", to); model.addAttribute("team", team); model.addAttribute("state", state);
            try {
                var query = Query.parse(from, to, team, state, page, LocalDate.now(AiExecutionHistory.ZONE));
                model.addAttribute("query", query);
                model.addAttribute("from", query.from().toString()); model.addAttribute("to", query.to().toString());
                model.addAttribute("team", query.team()); model.addAttribute("state", query.state());
                if (runPage) {
                    var detail = service.run(session, schema, runId, Integer.parseInt(taskPage));
                    if (detail == null) {
                        response.setStatus(404); model.addAttribute("loadError", UiMessages.text("ui.1a729bb6c301", "실행 기록이 없거나 보관 기간이 지났습니다."));
                    } else model.addAttribute("detail", detail);
                } else model.addAttribute("runs", service.page(session, metadata.selectedSchema(), query));
            } catch (AppException ex) {
                response.setStatus(403); model.addAttribute("loadError", ex.userMessage());
            } catch (IllegalArgumentException | java.time.DateTimeException ex) {
                response.setStatus(400); model.addAttribute("loadError", UiMessages.text("ui.f30475c4ad67", "기간은 1~31일, Team명은 128자 이내로 입력해 주세요. 페이지는 1~1,000까지 조회할 수 있습니다. 실행 ID와 상태도 확인해 주세요."));
            } catch (AiAgentExecutionRepository.AmbiguousTask ex) {
                response.setStatus(409); model.addAttribute("loadError", UiMessages.text("ui.88ed07f66444", "같은 실행 ID·순번에 여러 기록이 있어 정확히 구별할 수 없습니다."));
            } catch (RuntimeException ex) {
                response.setStatus(503); model.addAttribute("loadError", failure(ex));
            }
        }
        return "ai-agent-executions";
    }
    @GetMapping(PATH + "/task") @ResponseBody
    public ResponseEntity<?> task(@RequestParam String schema, @RequestParam String runId, @RequestParam long order,
                                  HttpServletRequest request) {
        var session = session(request);
        return json(session, () -> service.task(session, schema, runId, order));
    }
    @GetMapping(PATH + "/conversations") @ResponseBody
    public ResponseEntity<?> conversations(@RequestParam String schema, @RequestParam String runId, @RequestParam long order,
                                           @RequestParam(defaultValue = "1") int page, HttpServletRequest request) {
        var session = session(request);
        return json(session, () -> service.conversations(session, schema, runId, order, page));
    }
    private ResponseEntity<?> json(PoolSession session, Supplier<?> work) {
        if (session == null) return error(401, UiMessages.text("ui.9f0bb0f1f663", "다시 로그인해 주세요."));
        try {
            var data = work.get();
            return data == null ? error(404, UiMessages.text("ui.d7af1c9a8296", "Task 기록이 없거나 보관 기간이 지났습니다."))
                    : ResponseEntity.ok().header("Cache-Control", "no-store").body(data);
        } catch (AppException ex) { return error(403, ex.userMessage()); }
        catch (IllegalArgumentException ex) { return error(400, UiMessages.text("ui.a5b11683dbf5", "실행 ID·Task 순번·페이지를 확인해 주세요.")); }
        catch (AiAgentExecutionRepository.AmbiguousTask ex) { return error(409, UiMessages.text("ui.88ed07f66444", "같은 실행 ID·순번에 여러 기록이 있어 정확히 구별할 수 없습니다.")); }
        catch (RuntimeException ex) { return error(503, failure(ex)); }
    }
    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("error", message));
    }
    private PoolSession session(HttpServletRequest request) {
        var http = request.getSession(false);
        return http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
    }
    private String failure(RuntimeException error) {
        String view = error instanceof AiAgentExecutionRepository.QueryFailure failure ? failure.view() : "Agent history";
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
        int code = cause instanceof SQLException sql ? sql.getErrorCode() : 0;
        LoggerFactory.getLogger(AiAgentExecutionController.class).warn("Agent execution query error: view={} code={}", view, code);
        String detail = OracleErrorDetails.forDisplay(error);
        if (detail.isBlank() && cause instanceof IllegalStateException) detail = cause.getMessage();
        return UiMessages.text("ui.f86d70138cb5", "Agent 실행 조회 중 오류 · ") + view + " · Oracle code=" + code
                + (detail.isBlank() ? UiMessages.text("ui.cdbe1bfc7d63", " · DB 지원 여부와 조회 권한을 확인해 주세요.") : " · " + detail);
    }
}
