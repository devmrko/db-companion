package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.AccountSetup;
import com.dbcompanion.model.AccountSetup.*;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.service.AccountSetupService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/db/accounts")
public class AccountSetupController {
    private static final String PENDING=AccountSetupController.class.getName()+".pending";
    private static final String PRIVILEGE=AccountSetupController.class.getName()+".privilege";
    private record PrivilegePending(AccountSetupService.PrivilegePlan plan,PoolSession session) {}
    public record PrivilegeInput(String name,String operation) {}
    public record PrivilegeApply(String token,boolean confirmed) {}
    private record Pending(Plan plan,PoolSession session,long userId,boolean attempted) {}
    private final AccountSetupService service;
    public AccountSetupController(AccountSetupService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    @GetMapping public String page(HttpServletRequest r,Model model){var s=session(r);if(s==null)return "redirect:/login";model.addAttribute("info",s.metadata().info());model.addAttribute("activePage","accounts");model.addAttribute("languageReturn","/db/accounts");model.addAttribute("accountAdmin","ADMIN".equals(s.metadata().info().username())&&"ADMIN".equals(s.metadata().selectedSchema()));return "account-setup";}
    @GetMapping("/status") @ResponseBody public ResponseEntity<?> status(@RequestParam String name,HttpServletRequest r){return run(r,()->service.status(session(r),name));}
    @PostMapping("/privilege-preview") @ResponseBody public ResponseEntity<?> privilegePreview(@RequestBody PrivilegeInput input,HttpServletRequest r){return run(r,()->{
        r.getSession().removeAttribute(PRIVILEGE);var plan=service.privilegePreview(session(r),input.name(),input.operation());r.getSession().setAttribute(PRIVILEGE,new PrivilegePending(plan,session(r)));return plan;
    });}
    @PostMapping("/privilege-apply") @ResponseBody public ResponseEntity<?> privilegeApply(@RequestBody PrivilegeApply input,HttpServletRequest r){return run(r,()->{
        var pending=(PrivilegePending)r.getSession().getAttribute(PRIVILEGE);
        if(pending==null||pending.session()!=session(r)||!input.confirmed()||!pending.plan().token().equals(input.token())||!java.time.Instant.now().isBefore(pending.plan().expires()))throw AccountSetup.error(409,"expired");
        r.getSession().removeAttribute(PRIVILEGE);return service.privilegeApply(session(r),pending.plan());
    });}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody Input input,HttpServletRequest r){return run(r,()->{r.getSession().removeAttribute(PENDING);var plan=service.preview(session(r),input);r.getSession().setAttribute(PENDING,new Pending(plan,session(r),-1,false));return plan;});}
    @PostMapping("/create") @ResponseBody public ResponseEntity<?> create(@RequestBody Apply input,HttpServletRequest r){return run(r,()->{
        var p=pending(r,input);if(p.attempted())throw AccountSetup.error(409,"expired");AccountSetup.password(input.password());
        r.getSession().setAttribute(PENDING,new Pending(p.plan(),p.session(),-1,true));
        long id=service.create(p.session(),p.plan(),input.password());
        r.getSession().setAttribute(PENDING,new Pending(p.plan(),p.session(),id,true));
        return service.configure(p.session(),p.plan(),id);
    });}
    @PostMapping("/configure") @ResponseBody public ResponseEntity<?> configure(@RequestBody Apply input,HttpServletRequest r){return run(r,()->{var p=pending(r,input);if(p.userId()<0)throw AccountSetup.error(409,"identityChanged");return service.configure(p.session(),p.plan(),p.userId());});}
    private Pending pending(HttpServletRequest r,Apply input){var p=(Pending)r.getSession().getAttribute(PENDING);if(p==null||p.session()!=session(r))throw AccountSetup.error(409,"expired");AccountSetup.require(p.plan(),input);return p;}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> work){var s=session(r);if(s==null)return error(401,"Session expired");synchronized(s){try{AccountSetupService.admin(s);return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}catch(RuntimeException ex){return error(503,AccountSetup.error(503,"failed").getMessage());}}}
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) @ResponseBody public ResponseEntity<?> invalid(){return error(400,AccountSetup.error(400,"invalid").getMessage());}
}
