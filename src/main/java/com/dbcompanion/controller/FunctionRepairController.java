package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.FunctionCatalog.Failure;
import com.dbcompanion.service.FunctionRepairService;
import com.dbcompanion.service.FunctionRepairService.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller @RequestMapping("/db/functions/repair")
public class FunctionRepairController {
    private static final String STATE=FunctionRepairController.class.getName();
    private record State(PoolSession session,Source loaded,Source backup,Plan plan) {}
    public record Edit(String source,boolean restore) {}
    public record Apply(String token,boolean consent) {}
    private final FunctionRepairService service;
    public FunctionRepairController(FunctionRepairService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    private State state(HttpServletRequest r){var state=(State)r.getSession().getAttribute(STATE);if(state==null||state.session()!=session(r))throw FunctionRepairService.error(409,"stale");return state;}
    @GetMapping public String page(HttpServletRequest r,Model model){var s=session(r);if(s==null)return "redirect:/login";model.addAttribute("info",s.metadata().info());model.addAttribute("activePage","functions");model.addAttribute("languageReturn","/db/functions/repair");return "function-repair";}
    @GetMapping("/source") @ResponseBody public ResponseEntity<?> source(@RequestParam String name,HttpServletRequest r){return run(r,()->{
        var result=service.read(session(r),name);var previous=(State)r.getSession().getAttribute(STATE);
        var backup=previous!=null&&previous.session()==session(r)&&previous.backup()!=null&&previous.backup().objectId()==result.objectId()?previous.backup():null;
        r.getSession().setAttribute(STATE,new State(session(r),result,backup,null));return Map.of("current",result,"canRestore",backup!=null);
    });}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody Edit edit,HttpServletRequest r){return run(r,()->{
        var state=state(r);r.getSession().setAttribute(STATE,new State(state.session(),state.loaded(),state.backup(),null));
        if(edit.restore()&&state.backup()==null)throw FunctionRepairService.error(409,"stale");
        var plan=service.preview(state.session(),state.loaded(),edit.restore()?state.backup().source():edit.source());
        r.getSession().setAttribute(STATE,new State(state.session(),state.loaded(),state.backup(),plan));return plan;
    });}
    @PostMapping("/apply") @ResponseBody public ResponseEntity<?> apply(@RequestBody Apply input,HttpServletRequest r){return run(r,()->{
        var state=state(r);var plan=state.plan();FunctionRepairService.require(plan,input.token(),input.consent(),FunctionRepairService.owner(state.session()));
        // Preserve the first original source even if the outcome of the DDL is uncertain.
        Source backup=state.backup()==null?plan.before():state.backup();
        r.getSession().setAttribute(STATE,new State(state.session(),state.loaded(),backup,null));
        var result=service.apply(state.session(),plan);r.getSession().setAttribute(STATE,new State(state.session(),result,backup,null));return Map.of("current",result,"canRestore",true);
    });}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> action){var s=session(r);if(s==null)return ResponseEntity.status(401).body(Map.of("error","Session expired"));synchronized(s){try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}catch(Failure ex){return ResponseEntity.status(ex.status()).body(Map.of("error",ex.getMessage()));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(Map.of("error",FunctionRepairService.error(400,"invalid").getMessage()));}}}
}
