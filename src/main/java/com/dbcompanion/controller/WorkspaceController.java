package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.service.DatabaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.SQLException;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WorkspaceController {
    private final DatabaseService service;

    public WorkspaceController(DatabaseService service) { this.service = service; }

    @GetMapping("/tables")
    public String tables(HttpServletRequest request, HttpServletResponse response, Model model) {
        var session = database(request);
        if (session == null) return "redirect:/login";
        try {
            var page = service.tables(session);
            model.addAttribute("info", page.dashboard().info());
            model.addAttribute("schemas", page.dashboard().schemas());
            model.addAttribute("selectedSchema", page.dashboard().selectedSchema());
            model.addAttribute("tables", page.tables());
        } catch (RuntimeException ex) {
            logCode(ex);
            response.setStatus(503);
            model.addAttribute("loadError", UiMessages.text("ui.7905f7174fcd", "테이블 목록을 불러오지 못했습니다. 다시 시도해 주세요."));
        }
        model.addAttribute("activePage", "tables");
        return "tables";
    }

    @PostMapping("/schema")
    public String schema(@RequestParam(defaultValue = "") String schema,
                         @RequestParam(defaultValue = "/") String returnTo,
                         HttpServletRequest request, RedirectAttributes redirect) {
        var session = database(request);
        if (session == null) return "redirect:/login";
        try {
            service.selectSchema(session, schema);
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("schemaError", UiMessages.text("ui.47510c301627", "접근 가능한 스키마를 선택해 주세요."));
        } catch (RuntimeException ex) {
            logCode(ex);
            redirect.addFlashAttribute("schemaError", UiMessages.text("ui.922d013995ff", "스키마를 변경하지 못했습니다. 다시 시도해 주세요."));
        }
        return "redirect:" + safeReturn(returnTo);
    }

    @PostMapping("/schema/refresh")
    public String refreshSchemas(@RequestParam(defaultValue = "/") String returnTo,
                                 HttpServletRequest request, RedirectAttributes redirect) {
        var session = database(request);
        if (session == null) return "redirect:/login";
        try {
            service.refreshSchemas(session);
        } catch (RuntimeException ex) {
            logCode(ex);
            redirect.addFlashAttribute("schemaError", UiMessages.text("ui.69f46b96c62a", "스키마 목록을 갱신하지 못했습니다. 기존 목록을 유지합니다."));
        }
        return "redirect:" + safeReturn(returnTo);
    }

    @GetMapping("/tables/detail")
    public String detail(@RequestParam String schema, @RequestParam String table,
                         @RequestParam(defaultValue = "columns") String tab,
                         HttpServletRequest request, HttpServletResponse response, Model model) {
        var session = database(request);
        if (session == null) return "redirect:/login";
        String selectedTab = java.util.Set.of("columns", "comments", "annotations", "constraints", "indexes").contains(tab) ? tab : "columns";
        model.addAttribute("activePage", "tables");
        model.addAttribute("selectedTab", selectedTab);
        model.addAttribute("targetSchema", schema);
        model.addAttribute("targetTable", table);
        try {
            var detail = service.detail(session, schema, table, selectedTab);
            model.addAttribute("detail", detail);
            model.addAttribute("info", detail.dashboard().info());
            model.addAttribute("schemas", detail.dashboard().schemas());
            model.addAttribute("selectedSchema", detail.dashboard().selectedSchema());
        } catch (AppException ex) {
            response.setStatus(404);
            model.addAttribute("loadError", ex.userMessage());
        } catch (RuntimeException ex) {
            logCode(ex);
            response.setStatus(503);
            model.addAttribute("loadError", UiMessages.text("ui.17ced7cea5c2", "테이블 정보를 불러오지 못했습니다."));
        }
        return "table-detail";
    }

    private PoolSession database(HttpServletRequest request) {
        var session = request.getSession(false);
        return session == null ? null : (PoolSession) session.getAttribute(PoolSession.ATTRIBUTE);
    }

    private String safeReturn(String path) {
        if("/ontology".equals(path)||"/ontology-query".equals(path))return path;
        if("/db/scheduler".equals(path))return path;
        if(ExternalSourcesController.safeReturn(path))return path;
        return java.util.Set.of("/db/external-sources", "/ai-assistant", "/db/credentials", "/db/security", "/db/functions", "/tables", "/ai-profiles", "/ai-feedback", "/ai-agents", "/ai-executions", "/ai-executions/agents", "/vector-search").contains(path) ? path : "/";
    }

    @GetMapping("/tables/profiles")
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<?> tableProfiles(@RequestParam String schema,
            @RequestParam(required = false) String profile, HttpServletRequest request) {
        var session = database(request);
        if (session == null) return org.springframework.http.ResponseEntity.status(401).build();
        synchronized (session) {
            if (!schema.equals(session.metadata().selectedSchema())) {
                return org.springframework.http.ResponseEntity.status(409).body(java.util.Map.of("error", UiMessages.text("ui.b05af1b875ea", "스키마가 변경되었습니다. 화면을 새로고침해 주세요.")));
            }
            try {
                return org.springframework.http.ResponseEntity.ok(profile == null
                        ? service.profiles(session, null).profiles() : service.profileObjects(session, schema, profile));
            } catch (AppException ex) {
                return org.springframework.http.ResponseEntity.status(403).body(java.util.Map.of("error", ex.userMessage()));
            } catch (RuntimeException ex) {
                logCode(ex);
                return org.springframework.http.ResponseEntity.status(503).body(java.util.Map.of("error", UiMessages.text("ui.27dc2f4aa96b", "프로필 필터를 불러오지 못했습니다. 다시 시도해 주세요.")));
            }
        }
    }

    @GetMapping({"/ai-profiles", "/ai-profiles/detail"})
    public String profiles(@RequestParam(required = false) String profile,
                           HttpServletRequest request, HttpServletResponse response, Model model) {
        if (profile != null && profile.isBlank()) profile = null;
        var session = database(request);
        if (session == null) return "redirect:/login";
        var dashboard = service.dashboard(session);
        model.addAttribute("info", dashboard.info());
        model.addAttribute("schemas", dashboard.schemas());
        model.addAttribute("selectedSchema", dashboard.selectedSchema());
        model.addAttribute("activePage", "profiles");
        model.addAttribute("profileName", profile);
        try {
            var page = service.profiles(session, profile);
            model.addAttribute("page", page);
            model.addAttribute("profileEditable", dashboard.info().username().equals(dashboard.selectedSchema()));
            model.addAttribute("editableAttributes", page.attributes().stream()
                    .map(com.dbcompanion.model.AiProfileAttribute::name)
                    .filter(com.dbcompanion.common.db.ProfileEditPolicy::editableAttribute).toList());
        } catch (AppException ex) {
            response.setStatus(403);
            model.addAttribute("loadError", ex.userMessage());
        } catch (RuntimeException ex) {
            logCode(ex);
            response.setStatus(503);
            model.addAttribute("loadError", UiMessages.text("ui.7bf37cfac2ab", "프로필을 조회하지 못했습니다. Select AI 지원 여부와 조회 권한을 확인해 주세요."));
        }
        return "ai-profiles";
    }

    private void logCode(RuntimeException error) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
        int code = cause instanceof SQLException sql ? sql.getErrorCode() : 0;
        LoggerFactory.getLogger(WorkspaceController.class).warn("Workspace query error: code={}", code);
    }
}
