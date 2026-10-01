package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Properties;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.LocaleResolver;

@Controller
public class LanguageController {
    private final LocaleResolver resolver;
    public LanguageController(LocaleResolver resolver) { this.resolver=resolver; }

    @PostMapping("/language")
    public String change(@RequestParam String language, @RequestParam(defaultValue="/login") String returnTo,
            HttpServletRequest request, HttpServletResponse response) {
        if (!UiMessages.LANGUAGES.contains(language)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        resolver.setLocale(request,response,Locale.forLanguageTag(language));
        return "redirect:"+safeReturn(returnTo);
    }
    /** Only known read-only page routes; never redirect to a write endpoint or external site. */
    public static String safeReturn(String target) {
        if("/db/audit".equals(target))return target;
        if("/db/functions/ai-query".equals(target)||"/db/vpd".equals(target)||"/business-glossary".equals(target)||"/ontology".equals(target)||"/ontology-query".equals(target)||"/ai-test".equals(target)||"/ai-test/problems".equals(target))return target;
        if(target==null || target.contains("\\") || target.contains("\r") || target.contains("\n") || target.contains("#"))return "/login";
        String route=target.split("\\?",2)[0];
        return java.util.Set.of("/db/ords","/db/scheduler","/db/external-sources","/ai-assistant","/db/credentials","/db/security","/db/functions","/","/login","/tables","/tables/detail","/ai-profiles","/ai-profiles/detail",
                "/ai-agents","/ai-agents/team","/ai-agents/task","/ai-agents/object","/ai-agents/objects","/ai-executions","/ai-executions/agents",
                "/ai-executions/agents/run","/ai-executions/sql","/ai-executions/sql/sources","/ai-executions/sql/archive","/ai-feedback","/ai-feedback/detail","/vector-search",
                "/tables/history/probe","/ai-profiles/audit-probe","/ai-profiles/long-instruction-probe",
                "/ai-feedback/probe","/ai-executions/sql/access-setup").contains(route)?target:"/login";
    }
    @GetMapping(value="/i18n/messages.js",produces="application/javascript;charset=UTF-8") @ResponseBody
    public ResponseEntity<String> messages(Locale locale) throws java.io.IOException {
        var props=new Properties();
        try(var input=getClass().getResourceAsStream("/i18n/messages.properties")) {
            if(input==null)throw new IllegalStateException("Missing UI resource bundle");
            props.load(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8));
        }
        var values=new TreeMap<String,String>();
        for(var key:props.stringPropertyNames()) values.put(key,UiMessages.source().getMessage(key,null,UiMessages.supported(locale)));
        // Standalone JS response, never an HTML script body; additionally escape HTML delimiters.
        String json=new tools.jackson.databind.json.JsonMapper().writeValueAsString(values)
                .replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026")
                .replace("\u2028","\\u2028").replace("\u2029","\\u2029");
        return ResponseEntity.ok().header("Cache-Control","no-store").body("globalThis.DB_COMPANION_MESSAGES="+json+";");
    }
}
