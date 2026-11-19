package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.service.AiSetupService;
import com.dbcompanion.service.DatabaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AiSetupController {
    private final DatabaseService database;
    private final AiSetupService setup;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.dbcompanion.service.AiSetupHistoryService history;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.dbcompanion.service.AiSetupEvidenceService evidence;
    public AiSetupController(DatabaseService database, AiSetupService setup) { this.database = database; this.setup = setup; }
    @GetMapping("/ai-setup")
    public String page(@RequestParam(defaultValue="") String purpose, @RequestParam(defaultValue="") String profile,
                       HttpServletRequest request, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control", "no-store");
        var http = request.getSession(false);
        var session = http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
        if (session == null) return "redirect:/login";
        model.addAttribute("info", session.metadata().info());
        model.addAttribute("activePage", "ai-setup");
        model.addAttribute("languageReturn", "/ai-setup");
        model.addAttribute("purposes", com.dbcompanion.service.AiSetupCatalog.purposes());
        model.addAttribute("purpose", com.dbcompanion.service.AiSetupCatalog.purposes().contains(purpose) ? purpose : "");
        model.addAttribute("profile", profile);
        model.addAttribute("profiles", List.of());
        model.addAttribute("checks", List.of());
        model.addAttribute("observations", List.of());
        model.addAttribute("historyChecks", List.of());
        model.addAttribute("savedEvidence", List.of());
        if (!com.dbcompanion.service.AiSetupCatalog.purposes().contains(purpose)) return "ai-setup";
        synchronized (session) {
            if (!session.metadata().info().username().equals(session.metadata().selectedSchema())) {
                model.addAttribute("ownerRequired", true);
                return "ai-setup";
            }
            try {
                var profiles = database.profiles(session, null).profiles();
                model.addAttribute("profiles", profiles);
                if (!profile.isBlank()) {
                    model.addAttribute("checks", setup.diagnose(session, profile, purpose));
                    if(history!=null)model.addAttribute("historyChecks",history.diagnose(session,database.profiles(session,profile).attributes()));
                    if(evidence!=null)model.addAttribute("savedEvidence",evidence.read(session,profile));
                    var state=session.metadata().aiTest();var comparison=session.metadata().aiComparison().result();
                    model.addAttribute("observations", AiSetupService.observations(session.metadata().info().username(),profile,
                            java.util.Arrays.asList(state.latest(),state.prompt(),comparison.left(),comparison.right(),
                                    comparison.leftPrompt(),comparison.rightPrompt()),state.executionResult()));
                }
            } catch (RuntimeException ex) { model.addAttribute("unavailable", true); }
        }
        return "ai-setup";
    }
}
