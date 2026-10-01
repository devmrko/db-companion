package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.OrdsManagement.*;
import com.dbcompanion.service.OrdsManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/db/ords")
public class OrdsManagementController {
    private final OrdsManagementService service;
    public OrdsManagementController(OrdsManagementService service){this.service=service;}
    @GetMapping public String page(HttpServletRequest request,Model model){
        var session=session(request);if(session==null)return "redirect:/login";
        synchronized(session){var data=session.metadata();model.addAttribute("info",data.info());model.addAttribute("schemas",data.schemas());model.addAttribute("selectedSchema",data.selectedSchema());model.addAttribute("activePage","ords");model.addAttribute("languageReturn","/db/ords");}
        return "ords";
    }
    @GetMapping("/list") @ResponseBody public ResponseEntity<?> list(@RequestParam String schema,HttpServletRequest request){return invoke(request,s->service.list(s,schema));}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody Input body,HttpServletRequest request){return invoke(request,s->service.preview(s,body));}
    @PostMapping("/apply") @ResponseBody public ResponseEntity<?> apply(@RequestBody Apply body,HttpServletRequest request){return invoke(request,s->service.apply(s,body));}
    private ResponseEntity<?> invoke(HttpServletRequest request,Function<PoolSession,?> action){
        var session=session(request);if(session==null)return error(401,"expired");
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.apply(session));}
        catch(Failure ex){return error(ex.status(),ex.getMessage());}
        catch(RuntimeException ex){return error(503,"failed");}
    }
    private ResponseEntity<?> error(int status,String key){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("ords."+key,key),"code",key));}
    private PoolSession session(HttpServletRequest request){var http=request.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
}
