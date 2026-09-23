package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.OracleErrorDetails;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.AiFeedback.Query;
import com.dbcompanion.service.AiFeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.SQLException;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AiFeedbackController {
    private final AiFeedbackService service;
    public AiFeedbackController(AiFeedbackService service) { this.service = service; }
    @GetMapping("/ai-feedback")
    public String page(@RequestParam(defaultValue = "") String schema, @RequestParam(defaultValue = "") String profile,
                       @RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "") String type,
                       @RequestParam(defaultValue = "1") String page, @RequestParam(required = false) String id,
                       HttpServletRequest request, HttpServletResponse response, Model model) {
        var http = request.getSession(false);
        var session = http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
        if (session == null) return "redirect:/login";
        response.setHeader("Cache-Control", "no-store");
        synchronized (session) {
            model.addAttribute("activePage", "feedback");
            model.addAttribute("info", session.metadata().info());
            model.addAttribute("schemas", session.metadata().schemas());
            model.addAttribute("selectedSchema", session.metadata().selectedSchema());
            model.addAttribute("profile", profile); model.addAttribute("search", search); model.addAttribute("type", type);
            model.addAttribute("pageNumber", 1); model.addAttribute("detailMode", id != null);
            model.addAttribute("feedbackRowId",id);
            try {
                var query = new Query(schema, profile, search, type, Integer.parseInt(page));
                model.addAttribute("pageNumber", query.page());
                var result = service.load(session, query, id);
                model.addAttribute("result", result);
                if (id != null && !result.missingTable() && result.detail() == null) {
                    response.setStatus(404); model.addAttribute("loadError", UiMessages.text("ui.7c2f48ebdc37", "Feedback 항목을 찾을 수 없습니다. 목록을 새로고침해 주세요."));
                }
            } catch (AppException ex) {
                response.setStatus(403); model.addAttribute("loadError", ex.userMessage());
            } catch (IllegalArgumentException ex) {
                response.setStatus(400); model.addAttribute("loadError", UiMessages.text("ui.1f1c4358a941", "프로필·스키마와 검색 조건을 확인해 주세요. 검색은 500자, 페이지는 1~1,000까지 지원합니다."));
            } catch (RuntimeException ex) {
                Throwable cause = ex;
                while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
                int code = cause instanceof SQLException sql ? sql.getErrorCode() : 0;
                LoggerFactory.getLogger(AiFeedbackController.class).warn("Feedback query error: stage={} code={}", id == null ? "list" : "detail", code);
                response.setStatus(503);
                model.addAttribute("loadError", UiMessages.text("ui.88177dea7fbc", "Feedback을 조회하지 못했습니다. DB 지원 여부와 조회 권한을 확인해 주세요."));
                model.addAttribute("loadErrorDetails", "Oracle code=" + code + " · " + OracleErrorDetails.forDisplay(ex));
            }
        }
        return "ai-feedback";
    }
}
