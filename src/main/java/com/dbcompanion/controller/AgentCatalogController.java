package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.service.AgentCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.SQLException;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AgentCatalogController {
    private final AgentCatalogService service;
    public AgentCatalogController(AgentCatalogService service) { this.service = service; }

    @GetMapping("/ai-agents")
    public String teams(HttpServletRequest request, HttpServletResponse response, Model model) {
        var session = session(request, model);
        if (session == null) return "redirect:/login";
        try { model.addAttribute("teams", service.teams(session, session.metadata().selectedSchema())); }
        catch (RuntimeException ex) { failure(ex, response, model); }
        return "ai-agents";
    }

    @GetMapping("/ai-agents/team")
    public String team(@RequestParam String schema, @RequestParam String team,
                       HttpServletRequest request, HttpServletResponse response, Model model) {
        var session = session(request, model);
        if (session == null) return "redirect:/login";
        model.addAttribute("targetSchema", schema);
        model.addAttribute("teamName", team);
        objectEditing(session,schema,model);
        synchronized (session) {
            model.addAttribute("teamEditable", schema.equals(session.metadata().selectedSchema())
                    && schema.equals(session.metadata().info().username()));
            model.addAttribute("editableTeamAttributes", com.dbcompanion.common.db.TeamEditPolicy.EDITABLE);
        }
        try { model.addAttribute("teamPage", service.team(session, schema, team)); }
        catch (RuntimeException ex) { failure(ex, response, model); }
        return "ai-agent-team";
    }
    @GetMapping("/ai-agents/objects")
    public String objects(@RequestParam(defaultValue="AGENT") com.dbcompanion.model.AgentCatalog.Kind kind,HttpServletRequest request,HttpServletResponse response,Model model){
        var session=session(request,model);if(session==null)return "redirect:/login";
        if(kind==com.dbcompanion.model.AgentCatalog.Kind.TEAM)return "redirect:/ai-agents";
        model.addAttribute("objectKind",kind.name());
        try{model.addAttribute("objects",service.objects(session,session.metadata().selectedSchema(),kind));}catch(RuntimeException ex){failure(ex,response,model);}return "ai-agent-objects";
    }
    @GetMapping("/ai-agents/object")
    public String object(@RequestParam String schema,@RequestParam com.dbcompanion.model.AgentCatalog.Kind kind,@RequestParam String name,HttpServletRequest request,HttpServletResponse response,Model model){
        var session=session(request,model);if(session==null)return "redirect:/login";
        if(kind==com.dbcompanion.model.AgentCatalog.Kind.TEAM)return "redirect:/ai-agents";
        model.addAttribute("targetSchema",schema);model.addAttribute("objectKind",kind.name());model.addAttribute("objectName",name);objectEditing(session,schema,model);
        try{model.addAttribute("component",service.object(session,schema,kind,name));}catch(RuntimeException ex){failure(ex,response,model);}return "ai-agent-object";
    }

    @GetMapping("/ai-agents/task")
    public String task(@RequestParam String schema, @RequestParam String team, @RequestParam String task,
                       HttpServletRequest request, HttpServletResponse response, Model model) {
        var session = session(request, model);
        if (session == null) return "redirect:/login";
        model.addAttribute("targetSchema", schema);
        model.addAttribute("teamName", team);
        model.addAttribute("taskName", task);
        objectEditing(session,schema,model);
        try { model.addAttribute("taskPage", service.task(session, schema, team, task)); }
        catch (RuntimeException ex) { failure(ex, response, model); }
        return "ai-agent-task";
    }

    private void objectEditing(PoolSession session,String schema,Model model) {
        synchronized(session) {
            model.addAttribute("objectEditable",schema.equals(session.metadata().selectedSchema())&&schema.equals(session.metadata().info().username()));
            var editable=new java.util.HashMap<String,java.util.Set<String>>();
            com.dbcompanion.common.db.AgentObjectEditPolicy.EDITABLE.forEach((kind,attributes)->editable.put(kind.name(),attributes));
            model.addAttribute("editableObjectAttributes",editable);
        }
    }

    private PoolSession session(HttpServletRequest request, Model model) {
        var http = request.getSession(false);
        var session = http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
        if (session != null) {
            synchronized (session) {
                var metadata = session.metadata();
                model.addAttribute("info", metadata.info());
                model.addAttribute("schemas", metadata.schemas());
                model.addAttribute("selectedSchema", metadata.selectedSchema());
                model.addAttribute("activePage", "agents");
            }
        }
        return session;
    }

    private void failure(RuntimeException error, HttpServletResponse response, Model model) {
        if (error instanceof AppException app) {
            response.setStatus(400);
            model.addAttribute("loadError", app.userMessage());
            return;
        }
        if (error instanceof IllegalStateException && error.getMessage() != null
                && error.getMessage().startsWith("Required catalog column missing:")) {
            LoggerFactory.getLogger(AgentCatalogController.class).warn("Agent catalog mapping error: {}", error.getMessage());
            response.setStatus(503);
            model.addAttribute("loadError", UiMessages.text("ui.c790597e8157", "DB 조회 뷰의 컬럼 구성을 확인해야 합니다. 서버 로그에 누락된 컬럼을 기록했습니다."));
            return;
        }
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
        int code = cause instanceof SQLException sql ? sql.getErrorCode() : 0;
        LoggerFactory.getLogger(AgentCatalogController.class).warn("Agent catalog query error: code={}", code);
        response.setStatus(503);
        model.addAttribute("loadError", UiMessages.text("ui.3b5c483026a0", "Select AI Agent 정보를 조회하지 못했습니다. DB 지원 여부와 선택 스키마의 조회 권한을 확인해 주세요."));
    }
}
