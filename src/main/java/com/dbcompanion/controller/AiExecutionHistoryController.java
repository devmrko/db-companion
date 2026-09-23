package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.OracleErrorDetails;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.AiExecutionHistory;
import com.dbcompanion.model.AiExecutionHistory.Query;
import com.dbcompanion.repository.AiExecutionHistoryRepository;
import com.dbcompanion.service.AiExecutionHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.sql.SQLException;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AiExecutionHistoryController {
    private final AiExecutionHistoryService service;
    public AiExecutionHistoryController(AiExecutionHistoryService service) { this.service = service; }

    @GetMapping("/ai-executions")
    public String page(@RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
                       @RequestParam(defaultValue = "") String profile, @RequestParam(defaultValue = "") String question,
                       @RequestParam(defaultValue = "1") String page, HttpServletRequest request,
                       HttpServletResponse response, Model model) {
        var session = session(request);
        if (session == null) return "redirect:/login";
        response.setHeader("Cache-Control", "no-store");
        synchronized (session) {
            var metadata = session.metadata();
            model.addAttribute("info", metadata.info()); model.addAttribute("schemas", metadata.schemas());
            model.addAttribute("selectedSchema", metadata.selectedSchema()); model.addAttribute("activePage", "executions");
            // Preserve rejected inputs so the user can correct them without a hidden fallback query.
            model.addAttribute("from", from); model.addAttribute("to", to);
            model.addAttribute("profile", profile); model.addAttribute("question", question);
            try {
                var query = Query.parse(from, to, profile, question, page, LocalDate.now(AiExecutionHistory.ZONE));
                model.addAttribute("query", query);
                model.addAttribute("from", query.from().toString()); model.addAttribute("to", query.to().toString());
                model.addAttribute("profile", query.profile()); model.addAttribute("question", query.question());
                model.addAttribute("history", service.page(session, metadata.selectedSchema(), query));
            } catch (AppException ex) {
                response.setStatus(403); model.addAttribute("loadError", ex.userMessage());
            } catch (IllegalArgumentException | DateTimeException ex) {
                response.setStatus(400); model.addAttribute("loadError", UiMessages.text("ui.b73856f95ee3", "기간은 1~31일, 프로필명은 128자, 질문은 500자 이내로 입력해 주세요. 페이지는 1~1,000까지 조회할 수 있습니다."));
            } catch (RuntimeException ex) {
                response.setStatus(503); model.addAttribute("loadError", failure(ex, "list"));
            }
        }
        return "ai-executions";
    }

    @GetMapping("/ai-executions/detail")
    @ResponseBody
    public ResponseEntity<?> detail(@RequestParam String schema, @RequestParam String id, HttpServletRequest request) {
        var session = session(request);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", UiMessages.text("ui.9f0bb0f1f663", "다시 로그인해 주세요.")));
        try {
            var result = service.detail(session, schema, id);
            return result == null ? ResponseEntity.status(404).body(Map.of("error", UiMessages.text("ui.00bf038f6214", "기록이 없거나 보관 기간이 지났습니다.")))
                    : ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
        } catch (AppException ex) {
            return ResponseEntity.status(403).body(Map.of("error", ex.userMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", UiMessages.text("ui.6f29fc7d9dfb", "기록 ID를 확인해 주세요.")));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(503).body(Map.of("error", failure(ex, "detail")));
        }
    }
    private PoolSession session(HttpServletRequest request) {
        var http = request.getSession(false);
        return http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
    }
    private String failure(RuntimeException error, String stage) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
        int code = cause instanceof SQLException sql ? sql.getErrorCode() : 0;
        LoggerFactory.getLogger(AiExecutionHistoryController.class).warn("AI execution history query error: view={} stage={} code={}",
                AiExecutionHistoryRepository.VIEW, stage, code);
        String detail = OracleErrorDetails.forDisplay(error);
        return UiMessages.text("ui.91fef6b32c3d", "질문·응답 ") + (stage.equals("list") ? UiMessages.text("ui.f07b32009d77", "목록") : UiMessages.text("ui.d211d97f6bc0", "상세")) + UiMessages.text("ui.7001fd691a1d", " 조회 중 오류 · ")
                + AiExecutionHistoryRepository.VIEW + " · Oracle code=" + code
                + (detail.isBlank() ? UiMessages.text("ui.cdbe1bfc7d63", " · DB 지원 여부와 조회 권한을 확인해 주세요.") : " · " + detail);
    }
}
