package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.SemanticContext;
import com.dbcompanion.service.SemanticContextService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-profiles/semantic")
public class SemanticContextController {
    private static final String GRANT=SemanticContextController.class.getName()+".grant";
    private static final String EDIT=SemanticContextController.class.getName()+".edit";
    private final SemanticContextService service;
    public SemanticContextController(SemanticContextService service) { this.service = service; }
    @GetMapping public ResponseEntity<?> status(@RequestParam String schema, HttpServletRequest request) {
        return invoke(request, session -> service.status(session, schema));
    }
    @PostMapping public ResponseEntity<?> create(@RequestBody SemanticContext.Create input, HttpServletRequest request) {
        return invoke(request, session -> service.create(session, input));
    }
    public record User(String username) {}
    public record Apply(String token,boolean consent) {}
    @GetMapping("/edit") public ResponseEntity<?> edit(@RequestParam String schema,@RequestParam String name,HttpServletRequest r){return invoke(r,s->service.editForm(s,schema,name));}
    @PostMapping("/edit-preview") public ResponseEntity<?> editPreview(@RequestBody SemanticContext.Edit input,HttpServletRequest r){return invoke(r,s->{var plan=service.editPreview(s,input);r.getSession().setAttribute(EDIT,plan);return plan;});}
    @PostMapping("/edit-apply") public ResponseEntity<?> editApply(@RequestBody Apply input,HttpServletRequest r){return invoke(r,s->{synchronized(s){
        var plan=(SemanticContextService.EditPlan)r.getSession().getAttribute(EDIT);
        if(!input.consent()||plan==null||!plan.token().equals(input.token()))throw SemanticContext.error(400,"confirm");
        r.getSession().removeAttribute(EDIT);return service.editApply(s,plan);
    }});}
    @GetMapping("/access/users") public ResponseEntity<?> users(HttpServletRequest request) {return invoke(request,service::accessUsers);}
    @GetMapping("/access") public ResponseEntity<?> access(@RequestParam String username,HttpServletRequest request) {return invoke(request,s->service.access(s,username));}
    @PostMapping("/access/preview") public ResponseEntity<?> preview(@RequestBody User input,HttpServletRequest request) {return invoke(request,s->{
        var preview=service.accessPreview(s,input.username());request.getSession().setAttribute(GRANT,preview);return preview;
    });}
    @PostMapping("/access/grant") public ResponseEntity<?> grant(@RequestBody Apply input,HttpServletRequest request) {return invoke(request,s->{
        synchronized(s){
            var preview=(SemanticContextService.Grant)request.getSession().getAttribute(GRANT);
            if(!input.consent()||preview==null||!preview.token().equals(input.token()))throw SemanticContext.error(400,"accessConfirm");
            request.getSession().removeAttribute(GRANT);return service.grantAccess(s,preview);
        }
    });}
    private ResponseEntity<?> invoke(HttpServletRequest request, Function<PoolSession, Object> action) {
        var http = request.getSession(false);
        var session = http == null ? null : (PoolSession) http.getAttribute(PoolSession.ATTRIBUTE);
        if (session == null) return ResponseEntity.status(401).header("Cache-Control", "no-store").body(Map.of("error", "Session expired"));
        try {
            var result = action.apply(session);
            return ResponseEntity.status((result instanceof SemanticContext.Result r && !r.verified())||(result instanceof com.dbcompanion.model.SemanticChange.Result c&&!c.verified()) ? 202 : 200)
                    .header("Cache-Control", "no-store").body(result);
        } catch (MetadataEditException ex) {
            return ResponseEntity.status(ex.status()).header("Cache-Control", "no-store").body(Map.of("error", ex.userMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(503).header("Cache-Control", "no-store")
                    .body(Map.of("error", SemanticContext.error(503, "unavailable").userMessage()));
        }
    }
}
