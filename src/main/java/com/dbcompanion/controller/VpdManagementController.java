package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.VpdManagement.*;
import com.dbcompanion.repository.DeepDataSecurityRepository;
import com.dbcompanion.service.VpdManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/db/vpd")
public class VpdManagementController {
    private final VpdManagementService service;
    public VpdManagementController(VpdManagementService service){this.service=service;}
    public record Request(Action action,Draft draft,String fingerprint) {}
    public record Confirmation(String token,String target,boolean confirmed,boolean gapConfirmed) {}
    @GetMapping
    public String page(HttpServletRequest request,Model model){
        var session=session(request);if(session==null)return "redirect:/login";
        synchronized(session){var data=session.metadata();model.addAttribute("info",data.info());model.addAttribute("schemas",data.schemas());model.addAttribute("selectedSchema",data.selectedSchema());model.addAttribute("activePage","vpd");}
        return "vpd-management";
    }
    @GetMapping("/tables") @ResponseBody
    public ResponseEntity<?> tables(@RequestParam String schema,HttpServletRequest request){return respond(request,()->service.tables(session(request),schema));}
    @GetMapping("/objects") @ResponseBody
    public ResponseEntity<?> objects(@RequestParam String schema,HttpServletRequest request){return respond(request,()->service.objects(session(request),schema));}
    @GetMapping("/list") @ResponseBody
    public ResponseEntity<?> list(@RequestParam String schema,@RequestParam String table,HttpServletRequest request){return respond(request,()->service.list(session(request),schema,table));}
    @GetMapping("/functions") @ResponseBody
    public ResponseEntity<?> functions(@RequestParam String schema,@RequestParam String owner,HttpServletRequest request){return respond(request,()->service.functions(session(request),schema,owner));}
    @PostMapping("/preview") @ResponseBody
    public ResponseEntity<?> preview(@RequestBody Request body,HttpServletRequest request){return respond(request,()->service.preview(session(request),body.action(),body.draft(),body.fingerprint()));}
    @PostMapping("/execute") @ResponseBody
    public ResponseEntity<?> execute(@RequestBody Confirmation body,HttpServletRequest request){return respond(request,()->service.execute(session(request),body.token(),body.target(),body.confirmed(),body.gapConfirmed()));}
    private ResponseEntity<?> respond(HttpServletRequest request,Supplier<?> work){
        if(session(request)==null)return error(401,"ui.b9c067f345b1");
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(Failure ex){return error(ex.status(),ex.getMessage());}
        catch(IllegalArgumentException ex){return error(400,"vpd.invalid");}
        catch(RuntimeException ex){return error(503,DeepDataSecurityRepository.accessError(ex)?"vpd.accessRequired":"vpd.readError");}
    }
    private ResponseEntity<?> error(int status,String key){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text(key,key)));}
    private PoolSession session(HttpServletRequest request){var http=request.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
}
