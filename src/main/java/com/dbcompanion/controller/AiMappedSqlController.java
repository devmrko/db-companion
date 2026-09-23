package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.OracleErrorDetails;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.AiMappedSql;
import com.dbcompanion.model.AiMappedSql.Access;
import com.dbcompanion.model.AiMappedSql.Query;
import com.dbcompanion.repository.AiMappedSqlRepository;
import com.dbcompanion.service.AiMappedSqlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
public class AiMappedSqlController {
    private final AiMappedSqlService service;
    public AiMappedSqlController(AiMappedSqlService service) { this.service = service; }
    @GetMapping("/ai-executions/sql")
    public String page(@RequestParam(defaultValue = "") String sqlId, @RequestParam(defaultValue = "") String question,
                       @RequestParam(defaultValue = "1") String page, HttpServletRequest request,
                       HttpServletResponse response, Model model) {
        var session = session(request);
        if (session == null) return "redirect:/login";
        response.setHeader("Cache-Control", "no-store");
        synchronized (session) {
            model.addAttribute("info", session.metadata().info());
            model.addAttribute("activePage", "executions");
            model.addAttribute("sqlAccess", Access.NOT_CHECKED);
            model.addAttribute("readGrantExample", AiMappedSql.readGrantExample(session.metadata().info().username()));
            // Do not show a schema selector on a source without an owner column.
            model.addAttribute("sqlId", sqlId); model.addAttribute("question", question);
            try {
                var query = Query.parse(sqlId, question, page);
                model.addAttribute("sqlId", query.sqlId()); model.addAttribute("question", query.question());
                model.addAttribute("history", service.page(session, query));
                model.addAttribute("sqlAccess", Access.AVAILABLE);
            } catch (IllegalArgumentException ex) {
                response.setStatus(400); model.addAttribute("loadError", UiMessages.text("ui.e6edf615c2f6", "SQL ID는 소문자 영숫자 13자, SQL 원문 검색은 500자 이내로 입력해 주세요. 페이지는 1~1,000까지 조회할 수 있습니다."));
            } catch (RuntimeException ex) {
                var failure = failure(ex, "list", session.metadata().info().username());
                response.setStatus(503); model.addAttribute("loadError", failure.summary());
                model.addAttribute("loadErrorDetails", failure.details());
                model.addAttribute("sqlAccess", Access.failure(failure.code()));
            }
        }
        return "ai-mapped-sql";
    }
    @GetMapping("/ai-executions/sql/detail")
    @ResponseBody
    public ResponseEntity<?> detail(@RequestParam String id, HttpServletRequest request) {
        var session = session(request);
        if (session == null) return reply(401, UiMessages.text("ui.9f0bb0f1f663", "다시 로그인해 주세요."));
        try {
            var result = service.detail(session, id);
            return result == null ? reply(404, UiMessages.text("ui.e531f882765e", "SQL 매핑이 메모리에 없습니다. 목록을 새로고침해 주세요."))
                    : ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
        } catch (AppException ex) { return reply(409, ex.userMessage()); }
        catch (IllegalArgumentException ex) { return reply(400, UiMessages.text("ui.a72307a6112c", "SQL 매핑 ID를 확인해 주세요.")); }
        catch (RuntimeException ex) {
            var failure = failure(ex, "detail", session.metadata().info().username());
            return reply(503, failure.summary() + "\n" + failure.details());
        }
    }
    private ResponseEntity<?> reply(int status, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("error", message));
    }
    private PoolSession session(HttpServletRequest request) {
        var http = request.getSession(false);
        return http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
    }
    private record Failure(String summary, String details, int code) {}
    public static String failureSummary(String username, int code, String stage) {
        if (code == 942 || code == 1031) {
            return "ADMIN".equals(username)
                    ? UiMessages.text("ui.3c509241fde4", "ADMIN 계정에서도 SQL 매핑 뷰에 접근할 수 없습니다. DB의 뷰 제공 여부와 권한을 확인해 주세요.")
                    : UiMessages.text("ui.3ee6922d33a5", "이 로그인 계정으로 SQL 매핑 뷰에 접근할 수 없습니다. ADMIN에서 뷰와 조회 권한을 확인해 주세요.");
        }
        return "Select AI SQL " + (stage.equals("list") ? UiMessages.text("ui.f07b32009d77", "목록") : UiMessages.text("ui.d211d97f6bc0", "상세")) + UiMessages.text("ui.628ee0d70bac", "을 조회하지 못했습니다. 오류 상세를 확인해 주세요.");
    }
    private Failure failure(RuntimeException error, String stage, String username) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
        int code = cause instanceof SQLException sql ? sql.getErrorCode() : 0;
        LoggerFactory.getLogger(AiMappedSqlController.class).warn("AI SQL mapping query error: view={} stage={} code={}", AiMappedSqlRepository.VIEW, stage, code);
        String detail = OracleErrorDetails.forDisplay(error);
        return new Failure(failureSummary(username, code, stage), AiMappedSqlRepository.VIEW
                + " · Oracle code=" + code + (detail.isBlank() ? "" : " · " + detail), code);
    }
}
