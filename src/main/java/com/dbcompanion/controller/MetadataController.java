package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.MetadataEdit.*;
import com.dbcompanion.service.MetadataService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class MetadataController {
    private final MetadataService service;
    private final com.dbcompanion.service.MetadataHistoryService history;
    public MetadataController(MetadataService service, com.dbcompanion.service.MetadataHistoryService history) { this.service = service; this.history = history; }

    @GetMapping("/tables/history/state")
    public ResponseEntity<?> historyState(@RequestParam String schema, @RequestParam String table,
            @RequestParam(defaultValue = "false") boolean refresh, HttpServletRequest request) {
        return invoke(request, () -> history.state(session(request), new Target(schema, table, null),refresh));
    }
    @GetMapping("/tables/history")
    public ResponseEntity<?> history(@RequestParam String schema, @RequestParam String table, @RequestParam(defaultValue = "1") int page, HttpServletRequest request) {
        return invoke(request, () -> history.history(session(request), new Target(schema, table, null), page));
    }
    @PostMapping("/tables/history/toggle")
    public ResponseEntity<?> toggleHistory(@RequestBody com.dbcompanion.model.MetadataHistory.Toggle body, HttpServletRequest request) {
        return invoke(request, () -> history.toggle(session(request), new Target(body.schema(), body.table(), null), body.enabled()));
    }
    @PostMapping("/tables/history/upgrade")
    public ResponseEntity<?> upgradeHistory(@RequestBody com.dbcompanion.model.MetadataHistory.Toggle body, HttpServletRequest request) {
        return invoke(request, () -> history.upgradeCode(session(request), new Target(body.schema(), body.table(), null)));
    }

    @GetMapping("/tables/history/readiness")
    public ResponseEntity<?> historyReadiness(@RequestParam String schema, @RequestParam String table,
            HttpServletRequest request) {
        return invoke(request, () -> service.historyReadiness(session(request), new Target(schema, table, null)));
    }

    @GetMapping("/tables/metadata")
    public ResponseEntity<?> edit(@RequestParam String schema, @RequestParam String table,
            @RequestParam(required = false) String column, @RequestParam String kind,
            @RequestParam(required = false) String name, HttpServletRequest request) {
        return invoke(request, () -> service.edit(session(request), new Target(schema, table, column), kind, name));
    }
    @PostMapping("/tables/metadata")
    public ResponseEntity<?> save(@RequestBody SaveRequest body, HttpServletRequest request) {
        return invoke(request, () -> service.save(session(request), body));
    }
    @PostMapping("/tables/metadata/restore-form")
    public ResponseEntity<?> restoreForm(@RequestBody RestoreRequest body,HttpServletRequest request) {
        return invoke(request,()->service.restoreForm(session(request),body));
    }
    private ResponseEntity<?> invoke(HttpServletRequest request, Supplier<Object> work) {
        if (session(request) == null) return ResponseEntity.status(401).body(Map.of("error", UiMessages.text("ui.e0cca5f2b4e4", "로그인 세션이 만료되었습니다.")));
        try {
            Object result = work.get();
            return ResponseEntity.status(result instanceof SaveResult saved && !saved.verified() ? 202 : 200)
                    .header("Cache-Control", "no-store").body(result);
        } catch (MetadataEditException ex) {
            return ResponseEntity.status(ex.status()).body(Map.of("error", ex.userMessage()));
        } catch (RuntimeException ex) {
            Throwable cause = ex;
            while (cause.getCause() != null && !(cause instanceof java.sql.SQLException)) cause = cause.getCause();
            int code = cause instanceof java.sql.SQLException sql ? sql.getErrorCode() : 0;
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Metadata request error: code={}", code);
            return ResponseEntity.status(503).body(Map.of("error", UiMessages.text("ui.a43fb1ed434a", "DB 조회 또는 연결에 실패했습니다. 입력은 유지됩니다. (Oracle 오류 ") + code + ")"));
        }
    }
    private PoolSession session(HttpServletRequest request) {
        var http = request.getSession(false);
        return http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
    }
}
