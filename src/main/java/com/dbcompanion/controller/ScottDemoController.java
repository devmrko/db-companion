package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.service.ScottDemoService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller @RequestMapping("/db/scott-demo")
public class ScottDemoController {
    private static final String PLAN=ScottDemoController.class.getName()+".plan";
    private record Pending(ScottDemoService.Plan plan,PoolSession session) {}
    public record Apply(String token,boolean confirmed) {}
    private final ScottDemoService service;
    public ScottDemoController(ScottDemoService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    @GetMapping public String page(HttpServletRequest r,Model model){var s=session(r);if(s==null)return "redirect:/login";model.addAttribute("info",s.metadata().info());model.addAttribute("activePage","scott-demo");model.addAttribute("languageReturn","/db/scott-demo");return "scott-demo";}
    @GetMapping("/status") @ResponseBody public ResponseEntity<?> status(HttpServletRequest r){return run(r,()->service.status(session(r)));}
    @GetMapping("/verify") @ResponseBody public ResponseEntity<?> verify(HttpServletRequest r){return run(r,()->service.verify(session(r)));}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(HttpServletRequest r){return run(r,()->{r.getSession().removeAttribute(PLAN);var p=service.preview(session(r));r.getSession().setAttribute(PLAN,new Pending(p,session(r)));return p;});}
    @PostMapping("/install") @ResponseBody public ResponseEntity<?> install(@RequestBody Apply input,HttpServletRequest r){return run(r,()->{var p=(Pending)r.getSession().getAttribute(PLAN);if(p==null||p.session()!=session(r))throw ScottDemoService.error(409,"expired");ScottDemoService.require(p.plan(),input.token(),input.confirmed(),ScottDemoService.owner(session(r)));r.getSession().removeAttribute(PLAN);return service.install(session(r),p.plan());});}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> action){var s=session(r);if(s==null)return error(401,"Session expired");synchronized(s){try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}catch(RuntimeException ex){return error(503,ScottDemoService.error(503,"failed").getMessage());}}}
    private ResponseEntity<?> error(int status,String text){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",text));}
}
