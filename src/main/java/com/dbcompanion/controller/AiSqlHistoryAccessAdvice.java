package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.service.AiSqlHistoryAccessService;
import com.dbcompanion.service.AiSqlHistoryAccessService.Access;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {AiMappedSqlController.class, AiSqlHistoryController.class, SqlCacheArchiveController.class})
public class AiSqlHistoryAccessAdvice {
    private static final String CACHE = AiSqlHistoryAccessAdvice.class.getName();
    private record Cached(PoolSession owner, Instant expires, Map<String, Access> access) {}
    private final AiSqlHistoryAccessService service;
    public AiSqlHistoryAccessAdvice(AiSqlHistoryAccessService service) { this.service = service; }
    @ModelAttribute
    public void access(HttpServletRequest request, Model model) {
        // Do not add unrelated queries to detail or archive write endpoints.
        String path = request.getRequestURI();
        if (!request.getMethod().equals("GET") || !java.util.List.of("/ai-executions/sql", "/ai-executions/sql/sources", "/ai-executions/sql/archive").contains(path)) return;
        var http = request.getSession(false);
        if (http == null || !(http.getAttribute(PoolSession.ATTRIBUTE) instanceof PoolSession session)) return;
        synchronized (session) {
            var cached = http.getAttribute(CACHE) instanceof Cached value ? value : null;
            if (cached == null || cached.owner() != session || cached.expires().isBefore(Instant.now()) || "true".equals(request.getParameter("refresh"))) {
                cached = new Cached(session, Instant.now().plusSeconds(60), service.inspect(session));
                http.setAttribute(CACHE, cached);
            }
            model.addAttribute("sqlCapabilities", cached.access());
        }
    }
    public static Access selected(Model model, String name) {
        Object value = model.getAttribute("sqlCapabilities");
        return value instanceof Map<?, ?> map && map.get(name) instanceof Access access ? access : Access.denied(0);
    }
}
