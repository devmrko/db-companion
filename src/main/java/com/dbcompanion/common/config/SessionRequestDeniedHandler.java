package com.dbcompanion.common.config;

import com.dbcompanion.common.i18n.UiMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.json.JsonMapper;

/** Keep denied requests denied, without reflecting request parameters or security tokens. */
public final class SessionRequestDeniedHandler implements AccessDeniedHandler {
    private final LocaleResolver locales;
    private final JsonMapper json;
    public SessionRequestDeniedHandler(LocaleResolver locales,JsonMapper json){this.locales=locales;this.json=json;}
    @Override public void handle(HttpServletRequest request,HttpServletResponse response,AccessDeniedException exception) throws IOException {
        boolean csrf=exception instanceof CsrfException;
        String key=csrf?"requestDenied.csrf":"requestDenied.access";
        var locale=UiMessages.supported(locales.resolveLocale(request));
        String message=UiMessages.source().getMessage(key,null,locale);
        response.setStatus(403);response.setHeader("Cache-Control","no-store");response.setCharacterEncoding("UTF-8");
        String type=request.getContentType(),accept=request.getHeader("Accept");
        if((type!=null&&type.contains("application/json"))||(accept!=null&&accept.contains("application/json"))){
            response.setContentType("application/json");
            response.getWriter().write(json.writeValueAsString(Map.of("code",csrf?"REQUEST_VERIFICATION_FAILED":"ACCESS_DENIED","error",message)));
        }else{
            response.setContentType("text/html");
            String label=UiMessages.source().getMessage("requestDenied.login",null,locale);
            response.getWriter().write("<!doctype html><html lang=\""+locale.toLanguageTag()+"\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>DB Companion</title><link rel=\"stylesheet\" href=\"/css/common.css\"></head><body><main class=\"app-content\"><h1>DB Companion</h1><p role=\"alert\">"+HtmlUtils.htmlEscape(message)+"</p><a href=\"/login\">"+HtmlUtils.htmlEscape(label)+"</a></main></body></html>");
        }
    }
}
