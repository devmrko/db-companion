package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.service.AgentRelationships;
import com.dbcompanion.service.DatabaseService;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogPreviewController {
    private final DatabaseService service;
    public CatalogPreviewController(DatabaseService service) { this.service = service; }

    @GetMapping("/catalog-preview/profile")
    public ResponseEntity<?> profile(@RequestParam String schema, @RequestParam String name, HttpServletRequest request) {
        return read(request, schema, () -> {
            var canonical = AgentRelationships.optionalName(name);
            if (canonical == null) throw new IllegalArgumentException("Profile name required");
            return service.profiles(session(request), canonical);
        });
    }

    @GetMapping("/catalog-preview/routine")
    public ResponseEntity<?> routine(@RequestParam String schema, @RequestParam String name, HttpServletRequest request) {
        return read(request, schema, () -> service.routineSource(session(request), schema, name));
    }

    private ResponseEntity<?> read(HttpServletRequest request, String schema, Supplier<Object> work) {
        var session = session(request);
        if (session == null) return error(401, UiMessages.text("ui.b9c067f345b1", "로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
        synchronized (session) {
            if (!schema.equals(session.metadata().selectedSchema()))
                return error(409, UiMessages.text("ui.b05af1b875ea", "스키마가 변경되었습니다. 화면을 새로고침해 주세요."));
            try { return ResponseEntity.ok().header("Cache-Control", "no-store").body(work.get()); }
            catch (AppException ex) { return error(403, ex.userMessage()); }
            catch (IllegalArgumentException ex) { return error(400, UiMessages.text("ui.19aab2f2cd6c", "등록된 이름의 형식을 확인해 주세요. 로컬 객체 이름만 조회할 수 있습니다.")); }
            catch (RuntimeException ex) {
                Throwable cause = ex;
                while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
                int code = cause instanceof SQLException sql ? sql.getErrorCode() : 0;
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Catalog preview error: code={}", code);
                return error(503, UiMessages.text("ui.f6c82873d297", "정보를 조회하지 못했습니다. DB 지원 여부와 조회 권한을 확인해 주세요."));
            }
        }
    }

    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("error", message));
    }
    private PoolSession session(HttpServletRequest request) {
        var http = request.getSession(false);
        return http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
    }
}
