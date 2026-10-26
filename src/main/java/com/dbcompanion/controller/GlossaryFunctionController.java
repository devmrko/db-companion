package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.GlossaryFunction.*;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.GlossaryFunctionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/db/functions/glossary-search")
public class GlossaryFunctionController {
    private static final String PREVIEW=GlossaryFunctionController.class.getName()+".preview";
    private final GlossaryFunctionService service;
    public GlossaryFunctionController(GlossaryFunctionService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    @GetMapping public String page(HttpServletRequest r,HttpServletResponse response,Model model){
        var session=session(r);if(session==null)return "redirect:/login";
        response.setHeader("Cache-Control","no-store");
        synchronized(session){model.addAttribute("info",session.metadata().info());model.addAttribute("activePage","functions");}
        return "glossary-function";
    }
    @GetMapping("/status") @ResponseBody public ResponseEntity<?> status(HttpServletRequest r){return respond(r,()->service.status(session(r)));}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(HttpServletRequest r){
        return respond(r,()->{var value=service.preview(session(r));r.getSession().setAttribute(PREVIEW,value);return value;});
    }
    @PostMapping("/create") @ResponseBody public ResponseEntity<?> create(@RequestBody Apply input,HttpServletRequest r){
        return respond(r,()->{
            var value=(Preview)r.getSession().getAttribute(PREVIEW);
            if(!input.consent()||value==null||!value.token().equals(input.token()))throw new IllegalArgumentException("Confirm the displayed code before creation");
            r.getSession().removeAttribute(PREVIEW);
            return service.install(session(r),value);
        });
    }
    @PostMapping("/test") @ResponseBody public ResponseEntity<?> test(@RequestBody Question input,HttpServletRequest r){
        return respond(r,()->service.search(session(r),input));
    }
    private ResponseEntity<?> respond(HttpServletRequest r,Supplier<?> action){
        var session=session(r);
        if(session==null)return ResponseEntity.status(401).header("Cache-Control","no-store").body(Map.of("error","Session expired"));
        synchronized(session){
            try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}
            catch(IllegalArgumentException ex){return ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of("error",ex.getMessage()));}
            catch(RuntimeException ex){return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",CredentialCatalogRepository.error(ex),"unconfirmed",true));}
        }
    }
}
