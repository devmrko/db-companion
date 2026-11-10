package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AgentEngine;
import com.dbcompanion.service.AgentEngineService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/ai-agent-engine")
public class AgentEngineController {
    private final AgentEngineService service;
    public AgentEngineController(AgentEngineService service) { this.service = service; }
    private PoolSession session(HttpServletRequest request) {
        var http = request.getSession(false);
        return http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
    }
    @GetMapping public String page(HttpServletRequest request, Model model) {
        var session = session(request);
        if (session == null) return "redirect:/login";
        synchronized (session) {
            var metadata = session.metadata();
            model.addAttribute("info", metadata.info());
            model.addAttribute("schemas", metadata.schemas());
            model.addAttribute("selectedSchema", metadata.selectedSchema());
            model.addAttribute("activePage", "engine");
        }
        return "ai-agent-engine";
    }
    @GetMapping("/capability") @ResponseBody
    public ResponseEntity<?> capability(@RequestParam String schema, HttpServletRequest request) {
        return invoke(request, s -> service.capability(s, schema));
    }
    @GetMapping("/agents") @ResponseBody
    public ResponseEntity<?> agents(@RequestParam String schema, HttpServletRequest request) {
        return invoke(request, s -> service.list(s, schema));
    }
    @GetMapping("/agent") @ResponseBody
    public ResponseEntity<?> agent(@RequestParam String schema, @RequestParam String name, HttpServletRequest request) {
        return invoke(request, s -> service.describe(s, schema, name));
    }
    @PostMapping("/run-stored") @ResponseBody
    public ResponseEntity<?> runStored(@RequestBody AgentEngine.StoredRun input, HttpServletRequest request) {
        return invoke(request, s -> service.runStored(s, input));
    }
    @PostMapping("/run") @ResponseBody
    public ResponseEntity<?> run(@RequestBody AgentEngine.Run input, HttpServletRequest request) {
        return invoke(request, s -> service.run(s, input));
    }
    private ResponseEntity<?> invoke(HttpServletRequest request, Function<PoolSession, Object> action) {
        var session = session(request);
        if (session == null) return response(401, Map.of("error", "로그인 세션이 만료되었습니다."));
        try { return response(200, action.apply(session)); }
        catch (MetadataEditException ex) { return response(ex.status(), Map.of("error", ex.userMessage())); }
        catch (RuntimeException ex) { return response(503, Map.of("error", "DB 연결과 API 접근 권한을 확인하세요.")); }
    }
    private ResponseEntity<?> response(int status, Object result) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(result);
    }
}
