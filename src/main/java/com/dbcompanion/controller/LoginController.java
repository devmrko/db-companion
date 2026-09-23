package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.exception.AppException;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.service.DatabaseService;
import com.dbcompanion.service.TnsCatalogService;
import com.dbcompanion.service.WalletCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LoginController {
    private final DatabaseService service;
    private final TnsCatalogService catalog;
    private final WalletCatalogService wallets;
    private final HttpSessionSecurityContextRepository repository;

    public LoginController(DatabaseService service, TnsCatalogService catalog, WalletCatalogService wallets, HttpSessionSecurityContextRepository repository) {
        this.service = service;
        this.catalog = catalog;
        this.wallets = wallets;
        this.repository = repository;
    }

    @GetMapping("/login")
    public String loginPage(@RequestParam(defaultValue = "") String walletId, Model model) {
        return renderLogin(walletId, model);
    }

    private String renderLogin(String walletId, Model model) {
        model.addAttribute("walletOptions", List.of());
        model.addAttribute("tnsOptions", List.of());
        try {
            var registered = wallets.wallets();
            model.addAttribute("walletOptions", registered.stream().map(WalletCatalogService.Wallet::option).toList());
            var selected = walletId == null || walletId.isBlank() ? registered.getFirst() : wallets.resolve(walletId);
            model.addAttribute("selectedWallet", selected.id());
            model.addAttribute("selectedWalletName", selected.name());
            model.addAttribute("tnsOptions", catalog.options(selected.id()));
        } catch (AppException ex) {
            model.addAttribute("configurationError", ex.userMessage());
        }
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam(defaultValue = "") String username,
                        @RequestParam(defaultValue = "") String password,
                        @RequestParam(defaultValue = "") String tnsAlias,
                        @RequestParam(defaultValue = "") String walletId,
                        HttpServletRequest request, HttpServletResponse response, Model model) {
        DatabaseService.LoginResult result = null;
        try {
            result = service.login(username, password, tnsAlias, walletId);
            var previous = request.getSession(false);
            if (previous != null) previous.invalidate();
            request.getSession(true).setAttribute(PoolSession.ATTRIBUTE, result.session());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    result.info().username(), null, List.of(new SimpleGrantedAuthority("ROLE_DB_USER"))));
            SecurityContextHolder.setContext(context);
            repository.saveContext(context, request, response);
            return "redirect:/";
        } catch (AppException ex) {
            model.addAttribute("error", ex.userMessage());
        } catch (RuntimeException ex) {
            logCode(ex);
            model.addAttribute("error", UiMessages.text("ui.ebd5e674a407", "접속하지 못했습니다. DB 계정, Wallet 설정 및 네트워크를 확인해 주세요."));
        }
        if (result != null) result.session().close();
        model.addAttribute("selectedAlias", tnsAlias);
        response.setStatus(401);
        return renderLogin(walletId, model);
    }

    @GetMapping("/")
    public String dashboard(HttpServletRequest request, Model model) {
        var session = request.getSession(false);
        var database = session == null ? null : (PoolSession) session.getAttribute(PoolSession.ATTRIBUTE);
        if (database == null) return "redirect:/login";
        try {
            var dashboard = service.dashboard(database);
            model.addAttribute("info", dashboard.info());
            model.addAttribute("schemas", dashboard.schemas());
            model.addAttribute("selectedSchema", dashboard.selectedSchema());
            model.addAttribute("activePage", "overview");
            model.addAttribute("alias", database.alias());
            return "dashboard";
        } catch (RuntimeException ex) {
            logCode(ex);
            session.invalidate();
            SecurityContextHolder.clearContext();
            return "redirect:/login?expired";
        }
    }

    private void logCode(RuntimeException error) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
        var code = cause instanceof SQLException sql ? sql.getErrorCode() : 0;
        LoggerFactory.getLogger(LoginController.class).warn("Database request error: code={}", code);
    }
}
