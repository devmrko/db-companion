package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.OracleErrorDetails;
import com.dbcompanion.service.ProfileAuditProbeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@Profile("profile-audit-diagnostics")
public class ProfileAuditProbeController {
    private static final String NONCE = "profileAuditProbeNonce", CHECK = "profileAuditProbeCheck", RESULT = "profileAuditProbeResult", HISTORY = "profileHistoryVerification";
    private final ProfileAuditProbeService service;
    private final com.dbcompanion.service.ProfileHistoryVerificationService history;
    public ProfileAuditProbeController(ProfileAuditProbeService service, com.dbcompanion.service.ProfileHistoryVerificationService history) { this.service = service; this.history = history; }
    @GetMapping("/ai-profiles/audit-probe")
    public String page(HttpServletRequest request, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control", "no-store");
        var http = request.getSession(false);
        var pool = http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
        if (pool == null) return "redirect:/login";
        synchronized (pool) {
            if (http.getAttribute(NONCE) == null) http.setAttribute(NONCE, UUID.randomUUID().toString());
            model.addAttribute("nonce", http.getAttribute(NONCE));
            model.addAttribute("check", http.getAttribute(CHECK));
            model.addAttribute("result", http.getAttribute(RESULT));
            model.addAttribute("historyResult", http.getAttribute(HISTORY));
            model.addAttribute("loginUser", pool.metadata().info().username());
            model.addAttribute("selectedSchema", pool.metadata().selectedSchema());
        }
        return "profile-audit-probe";
    }
    @PostMapping("/ai-profiles/audit-probe")
    public String execute(@RequestParam String nonce, @RequestParam String action,
                          HttpServletRequest request, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control", "no-store");
        var http = request.getSession(false);
        var pool = http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
        if (pool == null) return "redirect:/login";
        if ("run-history".equals(action) || "refresh-history".equals(action))
            return historyAction(nonce, action, http, pool, response, model);
        synchronized (pool) {
            if (!nonce.equals(http.getAttribute(NONCE))) {
                response.setStatus(409); model.addAttribute("error", UiMessages.text("ui.1b3b9d8e3400", "이미 사용했거나 만료된 진단 요청입니다."));
                return "profile-audit-probe";
            }
            http.removeAttribute(NONCE);
            try {
                switch (action) {
                    case "inspect" -> http.setAttribute(CHECK, service.inspect(pool));
                    case "run", "run-instructions" -> {
                        if (http.getAttribute(CHECK) == null || http.getAttribute(RESULT) != null || http.getAttribute(HISTORY) != null)
                            throw new IllegalStateException("Preflight is required; repeated execution is not allowed");
                        http.removeAttribute(CHECK);
                        http.setAttribute(RESULT, service.run(pool, "run-instructions".equals(action)));
                    }
                    case "refresh" -> {
                        var report = (ProfileAuditProbeService.Report) http.getAttribute(RESULT);
                        if (report == null) throw new IllegalStateException("Run a diagnostic before refreshing evidence");
                        http.setAttribute(RESULT, service.refresh(pool, report));
                    }
                    default -> throw new IllegalArgumentException("Unknown diagnostic action");
                }
            } catch (RuntimeException ex) {
                response.setStatus(409);
                String oracle = OracleErrorDetails.forDisplay(ex);
                model.addAttribute("error", oracle.isEmpty() ? ex.getMessage() : oracle);
                model.addAttribute("result", http.getAttribute(RESULT));
                model.addAttribute("historyResult", http.getAttribute(HISTORY));
                return "profile-audit-probe";
            }
        }
        return "redirect:/ai-profiles/audit-probe";
    }
    private String historyAction(String nonce, String action, jakarta.servlet.http.HttpSession http,
            PoolSession pool, HttpServletResponse response, Model model) {
        try {
            com.dbcompanion.service.ProfileHistoryVerificationService.Report previous;
            synchronized (pool) {
                if (!nonce.equals(http.getAttribute(NONCE))) throw new IllegalStateException("Expired or already used verification request");
                http.removeAttribute(NONCE);
                previous = (com.dbcompanion.service.ProfileHistoryVerificationService.Report) http.getAttribute(HISTORY);
                if ("run-history".equals(action)) {
                    if (http.getAttribute(CHECK) == null || http.getAttribute(RESULT) != null || previous != null)
                        throw new IllegalStateException("Preflight is required; repeated execution is not allowed");
                    http.removeAttribute(CHECK);
                } else if (previous == null) throw new IllegalStateException("No history verification result");
            }
            // History mutations acquire the history service lock before the pool/session lock.
            // Do not hold the session lock while entering that service from this controller.
            var result = "run-history".equals(action) ? history.run(pool) : history.refresh(pool, previous);
            synchronized (pool) { http.setAttribute(HISTORY, result); }
            return "redirect:/ai-profiles/audit-probe";
        } catch (RuntimeException ex) {
            response.setStatus(409);
            String oracle = OracleErrorDetails.forDisplay(ex);
            model.addAttribute("error", oracle.isEmpty() ? ex.getMessage() : oracle);
            model.addAttribute("historyResult", http.getAttribute(HISTORY));
            return "profile-audit-probe";
        }
    }
}
