package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.service.ScenarioDataService;
import com.dbcompanion.service.TnsCatalogService;
import com.dbcompanion.service.WalletCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/db/scenario-data")
public class ScenarioDataController {
    private final ScenarioDataService service;
    private final WalletCatalogService wallets;
    private final TnsCatalogService catalog;

    public ScenarioDataController(ScenarioDataService service, WalletCatalogService wallets, TnsCatalogService catalog) {
        this.service = service; this.wallets = wallets; this.catalog = catalog;
    }

    @GetMapping
    public String page(HttpServletRequest request, Model model) {
        var session = request.getSession(false);
        var pool = session == null ? null : (PoolSession) session.getAttribute(PoolSession.ATTRIBUTE);
        if (pool == null) return "redirect:/login";
        model.addAttribute("info", pool.metadata().info());
        model.addAttribute("activePage", "scenario-data");
        model.addAttribute("languageReturn", "/db/scenario-data");
        model.addAttribute("presets", ScenarioDataService.PRESETS);
        model.addAttribute("walletOptions", List.of());
        try { model.addAttribute("walletOptions", wallets.wallets().stream().map(WalletCatalogService.Wallet::option).toList()); }
        catch (AppException ex) { model.addAttribute("configurationError", ex.userMessage()); }
        return "scenario-data";
    }

    @GetMapping("/aliases") @ResponseBody
    public ResponseEntity<?> aliases(@RequestParam String walletId) {
        try { return response(200, catalog.options(walletId)); }
        catch (AppException ex) { return response(400, Map.of("error", ex.userMessage())); }
    }

    @PostMapping("/generate") @ResponseBody
    public ResponseEntity<?> generate(@RequestBody ScenarioDataService.Input input) {
        try { return response(200, service.generate(input)); }
        catch (AppException ex) { return response(400, Map.of("error", ex.userMessage())); }
        catch (IllegalArgumentException ex) { return response(400, Map.of("error", ex.getMessage())); }
    }

    private ResponseEntity<?> response(int status, Object body) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
