package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.CredentialCatalog.Failure;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.CredentialEditService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller @RequestMapping("/db/credentials/edit")
public class CredentialEditController {
    private static final String KEY=CredentialEditController.class.getName();
    private record Pending(PoolSession session,CredentialEditService.Plan plan) {}
    public record Preview(String name,String attribute,boolean restore) {}
    public record Apply(String token,String value,boolean consent) {
        @Override public String toString(){return "CredentialApply[redacted]";}
    }
    private final CredentialEditService service;
    public CredentialEditController(CredentialEditService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    @GetMapping public String page(HttpServletRequest r,Model model){var s=session(r);if(s==null)return "redirect:/login";model.addAttribute("info",s.metadata().info());model.addAttribute("activePage","credentials");model.addAttribute("languageReturn","/db/credentials/edit");return "credential-edit";}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody Preview input,HttpServletRequest r){return run(r,()->{
        r.getSession().removeAttribute(KEY);var plan=service.preview(session(r),input.name(),input.attribute(),input.restore());
        r.getSession().setAttribute(KEY,new Pending(session(r),plan));
        return Map.of("token",plan.token(),"owner",plan.owner(),"name",plan.name(),"attribute",plan.attribute(),"restore",plan.restore(),"expires",plan.expires(),"sql",plan.sql());
    });}
    @PostMapping("/apply") @ResponseBody public ResponseEntity<?> apply(@RequestBody Apply input,HttpServletRequest r){return run(r,()->{
        var pending=(Pending)r.getSession().getAttribute(KEY);
        var plan=pending!=null&&pending.session()==session(r)?pending.plan():null;
        CredentialEditService.require(plan,input.token(),input.consent(),CredentialEditService.owner(session(r)),Instant.now());
        CredentialEditService.input(plan.attribute(),input.value());
        r.getSession().removeAttribute(KEY); // one shot even if Oracle outcome is uncertain
        return service.apply(session(r),plan,input.value());
    });}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> action){var s=session(r);if(s==null)return error(401,"stale",null);synchronized(s){try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}catch(Failure ex){return ResponseEntity.status(ex.status()).header("Cache-Control","no-store").body(Map.of("error",ex.getMessage()));}catch(RuntimeException ex){return error(503,"uncertain",ex);}}}
    private ResponseEntity<?> error(int status,String key,RuntimeException ex){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",CredentialEditService.error(status,key).getMessage()+(ex==null?"":" · "+CredentialCatalogRepository.error(ex))));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalid(){return error(400,"invalid",null);}
}
