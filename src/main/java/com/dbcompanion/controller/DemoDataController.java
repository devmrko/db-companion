package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.DemoDataPrompt;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.service.TnsCatalogService;
import com.dbcompanion.service.WalletCatalogService;
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
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/db/demo-data")
public class DemoDataController {
    private final WalletCatalogService wallets;
    private final TnsCatalogService catalog;
    public DemoDataController(WalletCatalogService wallets, TnsCatalogService catalog) {
        this.wallets = wallets; this.catalog = catalog;
    }
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
        model.addAttribute("presets", DemoDataPrompt.PRESETS);
        model.addAttribute("walletOptions", java.util.List.of());
        try { model.addAttribute("walletOptions", wallets.wallets().stream().map(WalletCatalogService.Wallet::option).toList()); }
        catch (AppException ex) { model.addAttribute("configurationError", ex.userMessage()); }
        return "demo-data";
    }
    @GetMapping("/aliases") @ResponseBody
    public ResponseEntity<?> aliases(@RequestParam String walletId, HttpServletRequest request) {
        if (session(request) == null) return ResponseEntity.status(401).body(Map.of("error", "Session expired"));
        try { return ResponseEntity.ok().header("Cache-Control", "no-store").body(catalog.options(walletId)); }
        catch (AppException ex) { return ResponseEntity.badRequest().body(Map.of("error", ex.userMessage())); }
    }
    @PostMapping("/prompt")
    @ResponseBody
    public ResponseEntity<?> prompt(@RequestBody DemoDataPrompt.ExportInput input, HttpServletRequest request) {
        var session = session(request);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Session expired"));
        synchronized (session) {
            try {
                return ResponseEntity.ok().header("Cache-Control", "no-store").body(
                        DemoDataPrompt.export(session.metadata().info().username(), session.metadata().selectedSchema(), input, catalog));
            } catch (AppException ex) {
                return ResponseEntity.badRequest().body(Map.of("error", ex.userMessage()));
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
            }
        }
    }
}
