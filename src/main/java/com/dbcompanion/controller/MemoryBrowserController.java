package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MemoryBrowserController {
    @GetMapping("/ai-memory") public String page(HttpServletRequest request, Model model) {
        var http = request.getSession(false);
        var session = http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
        if (session == null) return "redirect:/login";
        synchronized (session) {
            var metadata = session.metadata();
            model.addAttribute("info", metadata.info()); model.addAttribute("schemas", metadata.schemas());
            model.addAttribute("selectedSchema", metadata.selectedSchema()); model.addAttribute("activePage", "memory");
        }
        return "ai-memory";
    }
}
