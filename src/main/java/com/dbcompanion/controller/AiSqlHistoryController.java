package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiSqlHistory.*;
import com.dbcompanion.service.AiSqlHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AiSqlHistoryController {
    private final AiSqlHistoryService service;
    public AiSqlHistoryController(AiSqlHistoryService service) { this.service = service; }
    @GetMapping("/ai-executions/sql/sources")
    public String page(@RequestParam(defaultValue="cache") String source,
                       @RequestParam(defaultValue="all") String match,
                       @RequestParam(defaultValue="") String from, @RequestParam(defaultValue="") String to,
                       @RequestParam(defaultValue="") String sqlId, @RequestParam(defaultValue="") String text,
                       @RequestParam(required=false) String actor, @RequestParam(defaultValue="1") String page,
                       @RequestParam(defaultValue="false") boolean load, @RequestParam(defaultValue="false") boolean refresh,
                       @RequestParam(defaultValue="false") boolean awrAllowed, @RequestParam(defaultValue="false") boolean policies,
                       HttpServletRequest request, HttpServletResponse response, Model model) {
        var session = session(request); if (session == null) return "redirect:/login";
        response.setHeader("Cache-Control", "no-store");
        synchronized (session) {
            var today = LocalDate.now(ZoneOffset.UTC);
            model.addAttribute("info", session.metadata().info()); model.addAttribute("activePage", "executions");
            model.addAttribute("q", Query.parse("cache", "", "", "", "", "", "1", today));
            model.addAttribute("awrAllowed", awrAllowed); model.addAttribute("load", load);
            try {
                // Missing on first visit differs from explicitly clearing the visible scope filter.
                String scope = actor == null && "cache".equals(source) ? session.metadata().info().username() : actor;
                var query = Query.parse(source, from, to, sqlId, text, scope, page, match, today); model.addAttribute("q", query);
                if (refresh) {
                    session.metadata().sqlHistory().refresh(query.source());
                    // Remove the refresh flag: a subsequent browser reload must reuse the session result.
                    return "redirect:" + org.springframework.web.util.UriComponentsBuilder.fromPath("/ai-executions/sql/sources")
                            .queryParam("source", query.source()).queryParam("match", query.match()).queryParam("from", query.from()).queryParam("to", query.to())
                            .queryParam("sqlId", query.sqlId()).queryParam("text", query.text()).queryParam("actor", query.actor())
                            .queryParam("load", load).queryParam("awrAllowed", awrAllowed).queryParam("policies", policies)
                            .build().encode().toUriString();
                }
                boolean available = AiSqlHistoryAccessAdvice.selected(model, query.source().name()).available();
                model.addAttribute("sqlSourceBlocked", !available);
                if (load && available) {
                    var result = service.page(session, query, awrAllowed); model.addAttribute("result", result);
                    if (result.failure() != null) response.setStatus(503);
                }
                if (query.source() == Source.audit && policies && AiSqlHistoryAccessAdvice.selected(model, "policies").available()) model.addAttribute("policies", service.policies(session));
            } catch (IllegalArgumentException | java.time.DateTimeException ex) {
                response.setStatus(400); model.addAttribute("inputError", UiMessages.text("sqlh.invalid", "조회 조건을 확인해 주세요. 기간은 최대 31일이며 AWR은 사용 확인이 필요합니다."));
            }
        }
        return "ai-sql-history";
    }
    @GetMapping("/ai-executions/sql/sources/detail") @ResponseBody
    public ResponseEntity<?> detail(@RequestParam String id, HttpServletRequest request) {
        var session = session(request); if (session == null) return error(401, UiMessages.text("ui.9f0bb0f1f663", "다시 로그인해 주세요."));
        try {
            var detail = service.detail(session, id);
            return detail == null ? error(404, UiMessages.text("sqlh.missing", "기록이 없거나 목록이 만료됐습니다. 화면을 새로고침해 주세요."))
                    : ResponseEntity.ok().header("Cache-Control", "no-store").body(detail);
        } catch (IllegalArgumentException ex) { return error(400, UiMessages.text("sqlh.invalid", "조회 조건을 확인해 주세요.")); }
        catch (AmbiguousRecord ex) { return error(409, UiMessages.text("sqlh.ambiguous", "기록을 하나로 식별할 수 없습니다. 목록을 새로고침해 주세요.")); }
        catch (RuntimeException ex) { return error(503, AiSqlHistoryService.failure(ex, "detail").details()); }
    }
    @GetMapping("/ai-executions/sql/sources/candidates") @ResponseBody
    public ResponseEntity<?> candidates(@RequestParam String id, HttpServletRequest request) {
        var session=session(request); if(session==null) return error(401,UiMessages.text("ui.9f0bb0f1f663","다시 로그인해 주세요."));
        try {
            var result=service.candidates(session,id);
            return result==null?error(404,UiMessages.text("sqlh.candidates.stale","기준 커서가 변경되었거나 만료됐습니다. 목록을 새로고침해 주세요."))
                    :ResponseEntity.ok().header("Cache-Control","no-store").body(result);
        } catch(IllegalArgumentException ex) { return error(400,UiMessages.text("sqlh.invalid","조회 조건을 확인해 주세요.")); }
        catch(AmbiguousRecord ex) { return error(409,UiMessages.text("sqlh.ambiguous","기록을 하나로 식별할 수 없습니다. 목록을 새로고침해 주세요.")); }
        catch(RuntimeException ex) { return error(503,UiMessages.text("sqlh.candidates.failed","후보 조회를 완료하지 못했습니다. 기존 상세 정보는 그대로 확인할 수 있습니다.")); }
    }
    private ResponseEntity<?> error(int status, String text) { return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("error", text)); }
    private PoolSession session(HttpServletRequest request) {
        var http = request.getSession(false); return http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
    }
}
