package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.HistoryAccess.*;
import com.dbcompanion.service.HistoryAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class HistoryAccessController {
    private static final String PREVIEW="metadata.history.access.preview";
    private record StoredPreview(PoolSession session,Preview preview) {}
    private final HistoryAccessService service;
    public HistoryAccessController(HistoryAccessService service) { this.service=service; }
    @GetMapping("/tables/history/access")
    public ResponseEntity<?> preview(@RequestParam String schema,@RequestParam(required=false) String profile,
            @RequestParam(required=false) String table,HttpServletRequest request) {
        return invoke(request,()-> {
            synchronized(session(request)) {
                var preview=service.preview(session(request),schema,profile,table);
                request.getSession(false).setAttribute(PREVIEW,new StoredPreview(session(request),preview));return preview;
            }
        });
    }
    @PostMapping("/tables/history/access")
    public ResponseEntity<?> apply(@RequestBody Apply body,HttpServletRequest request) {
        return invoke(request,()-> {
            // One-use preview, consumed even on failure. A duplicate request never repeats DDL or grants.
            synchronized(session(request)) {
                var http=request.getSession(false);var stored=(StoredPreview)http.getAttribute(PREVIEW);
                http.removeAttribute(PREVIEW);
                return service.apply(session(request),stored!=null&&stored.session()==session(request)?stored.preview():null,body);
            }
        });
    }
    private PoolSession session(HttpServletRequest r) { var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE); }
    private ResponseEntity<?> invoke(HttpServletRequest r,Supplier<Object> work) {
        if(session(r)==null) return ResponseEntity.status(401).body(Map.of("error",UiMessages.text("ui.e0cca5f2b4e4","로그인 세션이 만료되었습니다.")));
        try { return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get()); }
        catch(MetadataEditException ex) { return ResponseEntity.status(ex.status()).body(Map.of("error",ex.userMessage())); }
        catch(RuntimeException ex) { return ResponseEntity.status(503).body(Map.of("error",UiMessages.text("history.access.failed","DB 작업을 확인하지 못했습니다."))); }
    }
}
