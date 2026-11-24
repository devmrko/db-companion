package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.DemoDataPrompt;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/db/demo-data")
public class DemoDataController {
    private PoolSession session(HttpServletRequest request) {
        var http = request.getSession(false);
        return http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
    }
    @GetMapping
    public String page(HttpServletRequest request, Model model) {
        var session = session(request);
        if (session == null) return "redirect:/login";
        model.addAttribute("info", session.metadata().info());
        model.addAttribute("activePage", "demo-data");
        model.addAttribute("languageReturn", "/db/demo-data");
        return "demo-data";
    }
    @PostMapping("/prompt")
    @ResponseBody
    public ResponseEntity<?> prompt(@RequestBody DemoDataPrompt.Input input, HttpServletRequest request) {
        var session = session(request);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Session expired"));
        synchronized (session) {
            try {
                return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("prompt",
                        DemoDataPrompt.build(session.metadata().info().username(), session.metadata().selectedSchema(), input)));
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
            }
        }
    }
}
