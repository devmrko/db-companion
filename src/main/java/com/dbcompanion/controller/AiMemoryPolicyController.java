package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AiMemoryPolicy;
import com.dbcompanion.service.AiMemoryPolicyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-profiles/memory-policy")
public class AiMemoryPolicyController {
    private final AiMemoryPolicyService service;
    public AiMemoryPolicyController(AiMemoryPolicyService service) { this.service = service; }
    @GetMapping public ResponseEntity<?> status(@RequestParam String schema, @RequestParam String profile, HttpServletRequest request) {
        return invoke(request, s -> service.status(s, schema, profile));
    }
    @PostMapping public ResponseEntity<?> create(@RequestBody AiMemoryPolicy.Create input, HttpServletRequest request) {
        return invoke(request, s -> service.create(s, input));
    }
    @GetMapping("/observations") public ResponseEntity<?> observations(@RequestParam String schema, @RequestParam String profile, HttpServletRequest request) {
        return invoke(request, s -> service.observations(s, schema, profile));
    }
    @GetMapping("/conversations") public ResponseEntity<?> conversations(@RequestParam String schema, @RequestParam String profile, HttpServletRequest request) {
        return invoke(request, s -> service.conversations(s, schema, profile));
    }
    @GetMapping("/store") public ResponseEntity<?> store(@RequestParam String schema, @RequestParam String name, HttpServletRequest request) {
        return invoke(request, s -> service.store(s, schema, name));
    }
    @PostMapping("/extract") public ResponseEntity<?> extract(@RequestBody AiMemoryPolicy.Extract input, HttpServletRequest request) {
        return invoke(request, s -> service.extract(s, input));
    }
    @GetMapping("/records") public ResponseEntity<?> records(@RequestParam String schema, @RequestParam String name,
            @RequestParam(defaultValue="all") String kind, @RequestParam(defaultValue="") String query,
            @RequestParam(defaultValue="0") int page, HttpServletRequest request) {
        return invoke(request, s -> service.browse(s, schema, name, kind, query, page));
    }
    @GetMapping("/record") public ResponseEntity<?> record(@RequestParam String schema, @RequestParam String name,
            @RequestParam String kind, @RequestParam String id, HttpServletRequest request) {
        return invoke(request, s -> service.record(s, schema, name, kind, id));
    }
    private ResponseEntity<?> invoke(HttpServletRequest request, Function<PoolSession,Object> action) {
        var http = request.getSession(false);
        var session = http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
        if (session == null) return response(401, Map.of("error", "로그인 세션이 만료되었습니다."));
        try {
            Object result = action.apply(session);
            return response(result instanceof AiMemoryPolicy.Result r && !r.verified() ? 202 : 200, result);
        } catch (MetadataEditException ex) { return response(ex.status(), Map.of("error", ex.userMessage())); }
        catch (RuntimeException ex) { return response(503, Map.of("error", "조회·처리를 완료하지 못했습니다. 선택한 소유자와 DB 연결을 확인하세요.")); }
    }
    private ResponseEntity<?> response(int status, Object value) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(value);
    }
}
