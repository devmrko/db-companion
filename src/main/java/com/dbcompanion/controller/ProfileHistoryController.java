package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.ProfileHistory.*;
import com.dbcompanion.service.ProfileHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-profiles/history")
public class ProfileHistoryController {
    private final ProfileHistoryService service;
    public ProfileHistoryController(ProfileHistoryService service) { this.service = service; }
    @GetMapping("/state")
    public ResponseEntity<?> state(@RequestParam String schema, HttpServletRequest request) {
        return invoke(request, session -> service.state(session, new Target(schema, null)));
    }
    @GetMapping
    public ResponseEntity<?> history(@RequestParam String schema, @RequestParam(required=false) String profile,
            @RequestParam(defaultValue="profile") String scope, @RequestParam(defaultValue="1") int page, HttpServletRequest request) {
        return invoke(request, session -> service.history(session, new Target(schema, profile), scope, page));
    }
    @PostMapping("/toggle")
    public ResponseEntity<?> toggle(@RequestBody Toggle body, HttpServletRequest request) {
        return invoke(request, session -> service.toggle(session, body));
    }
    @PostMapping("/collect")
    public ResponseEntity<?> collect(@RequestBody Target body, HttpServletRequest request) {
        return invoke(request, session -> service.collect(session, body));
    }
    @PostMapping("/install")
    public ResponseEntity<?> install(@RequestBody Target body,HttpServletRequest request) {
        return invoke(request,session->service.install(session,body));
    }
    private ResponseEntity<?> invoke(HttpServletRequest request, Function<PoolSession, Object> action) {
        var http = request.getSession(false);
        var session = http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
        if (session == null) return ResponseEntity.status(401).header("Cache-Control", "no-store").body(Map.of("error", UiMessages.text("ui.e0cca5f2b4e4", "로그인 세션이 만료되었습니다.")));
        try { return ResponseEntity.ok().header("Cache-Control", "no-store").body(action.apply(session)); }
        catch (MetadataEditException ex) { return ResponseEntity.status(ex.status()).header("Cache-Control", "no-store").body(Map.of("error", ex.userMessage())); }
        catch (RuntimeException ex) {
            Throwable cause = ex; while (cause.getCause() != null && !(cause instanceof java.sql.SQLException)) cause = cause.getCause();
            String message = cause instanceof java.sql.SQLException sql ? "Oracle code=" + sql.getErrorCode() + " · " + sql.getMessage() : cause.getClass().getSimpleName();
            return ResponseEntity.status(503).header("Cache-Control", "no-store").body(Map.of("error", UiMessages.text("ui.dea9b5e04ffc", "프로필 이력 조회 중 오류: ") + message + UiMessages.text("ui.5532e6b1bc5f", " · DB 변경은 수행하지 않았습니다.")));
        }
    }
}
