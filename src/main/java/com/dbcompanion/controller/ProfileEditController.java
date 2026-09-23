package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.ProfileEdit.*;
import com.dbcompanion.service.ProfileEditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProfileEditController {
    private final ProfileEditService service;
    public ProfileEditController(ProfileEditService service) { this.service=service; }
    @GetMapping("/ai-profiles/attribute")
    public ResponseEntity<?> edit(@RequestParam String schema, @RequestParam String profile,
            @RequestParam String attribute, HttpServletRequest request) {
        return invoke(request, () -> service.edit(session(request),new Target(schema,profile,attribute)));
    }
    @PostMapping("/ai-profiles/attribute")
    public ResponseEntity<?> save(@RequestBody SaveRequest body,HttpServletRequest request) {
        return invoke(request, () -> service.save(session(request),body));
    }
    @GetMapping("/ai-profiles/attribute/objects")
    public ResponseEntity<?> objects(@RequestParam String schema, @RequestParam String profile,
            @RequestParam String owner, @RequestParam(defaultValue="") String filter, HttpServletRequest request) {
        return invoke(request, () -> service.objects(session(request),new Target(schema,profile,"object_list"),owner,filter));
    }
    private ResponseEntity<?> invoke(HttpServletRequest request, Supplier<Object> action) {
        if (session(request)==null) return ResponseEntity.status(401).body(Map.of("error",UiMessages.text("ui.e0cca5f2b4e4", "로그인 세션이 만료되었습니다.")));
        try {
            Object result=action.get();
            return ResponseEntity.status(result instanceof SaveResult saved && !saved.verified()?202:200)
                    .header("Cache-Control","no-store").body(result);
        } catch (MetadataEditException ex) {
            return ResponseEntity.status(ex.status()).header("Cache-Control","no-store").body(Map.of("error",ex.userMessage()));
        } catch (RuntimeException ex) {
            int code=0;
            for (Throwable cause=ex;cause!=null;cause=cause.getCause())
                if (cause instanceof java.sql.SQLException sql) { code=sql.getErrorCode(); break; }
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Profile edit preflight error: code={}",code);
            return ResponseEntity.status(503).header("Cache-Control","no-store")
                    .body(Map.of("error",UiMessages.text("ui.5c6e09f32183", "프로필 사전 확인 또는 변경 전 이력 보관에 실패했습니다. 프로필 변경은 실행하지 않았습니다. (Oracle 오류 ")+code+")"));
        }
    }
    private PoolSession session(HttpServletRequest request) {
        var http=request.getSession(false);
        return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
    }
}
