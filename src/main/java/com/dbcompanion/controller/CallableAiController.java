package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.CallableAi.*;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.CallableAiService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/db/functions/ai-query")
public class CallableAiController {
    private static final String INSTALL=CallableAiController.class.getName()+".install",RUN=CallableAiController.class.getName()+".run",GRANT=CallableAiController.class.getName()+".grant";
    private final CallableAiService service;
    public CallableAiController(CallableAiService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    @GetMapping public String page(HttpServletRequest r,HttpServletResponse response,Model m){
        var session=session(r);if(session==null)return "redirect:/login";response.setHeader("Cache-Control","no-store");
        m.addAttribute("info",session.metadata().info());m.addAttribute("callableAdmin",session.metadata().info().username().equals("ADMIN"));m.addAttribute("activePage","functions");m.addAttribute("languageReturn","/db/functions/ai-query");return "callable-ai";
    }
    @GetMapping("/status") @ResponseBody public ResponseEntity<?> status(HttpServletRequest r){return respond(r,()->service.status(session(r)));}
    @GetMapping("/script") @ResponseBody public ResponseEntity<?> script(HttpServletRequest r){return respond(r,()->Map.of("sql",service.script(session(r))));}
    @PostMapping("/install-preview") @ResponseBody public ResponseEntity<?> installPreview(HttpServletRequest r){return respond(r,()->{var p=service.preview(session(r));r.getSession().setAttribute(INSTALL,p);return p;});}
    public record Apply(String token,boolean consent) {}
    public record User(String username) {}
    @GetMapping("/access/users") @ResponseBody public ResponseEntity<?> users(HttpServletRequest r){return respond(r,()->service.users(session(r)));}
    @GetMapping("/access") @ResponseBody public ResponseEntity<?> access(@RequestParam String username,HttpServletRequest r){return respond(r,()->service.access(session(r),username));}
    @PostMapping("/access/preview") @ResponseBody public ResponseEntity<?> grantPreview(@RequestBody User value,HttpServletRequest r){return respond(r,()->{var p=service.grantPreview(session(r),value.username());r.getSession().setAttribute(GRANT,p);return p;});}
    @PostMapping("/access/grant") @ResponseBody public ResponseEntity<?> grant(@RequestBody Apply value,HttpServletRequest r){return respond(r,()->{
        var p=(Grant)r.getSession().getAttribute(GRANT);if(!value.consent()||p==null||!p.token().equals(value.token()))throw new IllegalArgumentException("Confirmation required");
        r.getSession().removeAttribute(GRANT);return service.grant(session(r),p);
    });}
    @PostMapping("/install") @ResponseBody public ResponseEntity<?> install(@RequestBody Apply value,HttpServletRequest r){return respond(r,()->{
        var p=(Install)r.getSession().getAttribute(INSTALL);if(!value.consent()||p==null||!p.token().equals(value.token()))throw new IllegalArgumentException("Confirmation required");
        r.getSession().removeAttribute(INSTALL);return service.install(session(r),p);
    });}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody Request value,HttpServletRequest r){return respond(r,()->{
        var p=service.prepare(session(r),value);r.getSession().setAttribute(RUN,p);return p;
    });}
    @PostMapping("/run") @ResponseBody public ResponseEntity<?> run(@RequestBody Apply value,HttpServletRequest r){return respond(r,()->{
        var p=(Run)r.getSession().getAttribute(RUN);if(p==null||!p.token().equals(value.token())||!p.request().mode().equals("CONTEXT")&&!value.consent())throw new IllegalArgumentException("Confirmation required");
        r.getSession().removeAttribute(RUN);return service.run(session(r),p);
    });}
    private ResponseEntity<?> respond(HttpServletRequest r,Supplier<?> action){
        if(session(r)==null)return ResponseEntity.status(401).header("Cache-Control","no-store").body(Map.of("error","Session expired"));
        synchronized(session(r)){
            try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}
            catch(IllegalArgumentException ex){return ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of("error",ex.getMessage()));}
            catch(RuntimeException ex){return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",CredentialCatalogRepository.error(ex),"unconfirmed",true));}
        }
    }
}
