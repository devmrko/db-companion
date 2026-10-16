package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.service.AccountSetupService;
import com.dbcompanion.service.AiNetworkSetupService;
import com.dbcompanion.service.AiNetworkSetupService.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/db/accounts/ai-network")
public class AiNetworkSetupController {
    private static final String PENDING=AiNetworkSetupController.class.getName()+".pending";
    private record Pending(PoolSession session,Plan plan) {}
    public record Apply(String token,boolean confirmed) {}
    private final AiNetworkSetupService service;
    public AiNetworkSetupController(AiNetworkSetupService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    @PostMapping("/status") public ResponseEntity<?> status(@RequestBody Input input,HttpServletRequest r){return run(r,()->service.status(session(r),input));}
    @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody Input input,HttpServletRequest r){return run(r,()->{r.getSession().removeAttribute(PENDING);var p=service.preview(session(r),input);r.getSession().setAttribute(PENDING,new Pending(session(r),p));return p;});}
    @PostMapping("/apply") public ResponseEntity<?> apply(@RequestBody Apply input,HttpServletRequest r){return run(r,()->{
        var p=(Pending)r.getSession().getAttribute(PENDING);
        if(p==null||p.session()!=session(r)||!input.confirmed()||!p.plan().token().equals(input.token())||!Instant.now().isBefore(p.plan().expires()))throw AiNetworkSetupService.error(409,"expired");
        r.getSession().removeAttribute(PENDING);return service.apply(session(r),p.plan());
    });}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> work){var s=session(r);if(s==null)return error(401,"Session expired");synchronized(s){try{AccountSetupService.admin(s);return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}catch(RuntimeException ex){return error(503,AiNetworkSetupService.error(503,"failed").getMessage());}}}
    private ResponseEntity<?> error(int code,String message){return ResponseEntity.status(code).header("Cache-Control","no-store").body(Map.of("error",message));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<?> invalid(){return error(400,AiNetworkSetupService.error(400,"invalid").getMessage());}
}
