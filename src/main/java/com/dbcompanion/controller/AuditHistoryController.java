package com.dbcompanion.controller;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AuditHistory.*;
import com.dbcompanion.repository.AuditHistoryRepository;
import com.dbcompanion.service.AuditHistoryService;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/db/audit")
public class AuditHistoryController {
    private static final String PREVIEW=AuditHistoryController.class.getName()+".preview",SELECTION=AuditHistoryController.class.getName()+".selection";
    private final AuditHistoryService service;
    public AuditHistoryController(AuditHistoryService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    @GetMapping public String page(HttpServletRequest r,HttpServletResponse response,Model m){
        var s=session(r);if(s==null)return "redirect:/login";response.setHeader("Cache-Control","no-store");
        m.addAttribute("info",s.metadata().info());m.addAttribute("activePage","audit");m.addAttribute("languageReturn","/db/audit");
        m.addAttribute("today",LocalDate.now(ZoneOffset.UTC));m.addAttribute("from",LocalDate.now(ZoneOffset.UTC).minusDays(1));return "audit-history";
    }
    @GetMapping("/status") @ResponseBody public ResponseEntity<?> status(HttpServletRequest r){return run(r,()->service.status(session(r)));}
    @GetMapping("/help") @ResponseBody public ResponseEntity<?> help(HttpServletRequest r){return run(r,()->{
        var s=service.status(session(r));String view=s.source().isEmpty()?"AUDSYS.UNIFIED_AUDIT_TRAIL":s.source();
        return Map.of("sql",AuditHistorySql.help(view),"source",view);
    });}
    @GetMapping("/list") @ResponseBody public ResponseEntity<?> list(@RequestParam String from,@RequestParam String to,
        @RequestParam(defaultValue="original")String source,@RequestParam(defaultValue="dds")String scope,
        @RequestParam(defaultValue="")String dbUser,@RequestParam(defaultValue="")String endUser,
        @RequestParam(defaultValue="")String objectOwner,@RequestParam(defaultValue="")String objectName,
        @RequestParam(defaultValue="all")String outcome,@RequestParam(defaultValue="")String context,@RequestParam(defaultValue="1")int page,HttpServletRequest r){
        return run(r,()->{
            r.getSession().removeAttribute(SELECTION);
            var query=new Query(Source.valueOf(source),LocalDate.parse(from),LocalDate.parse(to),scope,dbUser,endUser,objectOwner,objectName,outcome,context,page);
            var result=service.page(session(r),query);var selections=new HashMap<String,Selection>();
            for(var row:result.rows())selections.put(row.get("RECORD_KEY").toString(),new Selection(query,row));
            r.getSession().setAttribute(SELECTION,selections);return result;
        });
    }
    @GetMapping("/detail") @ResponseBody public ResponseEntity<?> detail(@RequestParam String key,HttpServletRequest r){
        return run(r,()->{var selected=r.getSession().getAttribute(SELECTION);
            if(!(selected instanceof Map<?,?> map)||!(map.get(key) instanceof Selection item))throw new IllegalArgumentException("Search again");
            return service.detail(session(r),item);
        });
    }
    public record Prepare(Operation operation,int minutes,int retentionDays){}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody Prepare v,HttpServletRequest r){
        return run(r,()->{r.getSession().removeAttribute(PREVIEW);var p=service.preview(session(r),v.operation(),new Settings(v.minutes(),v.retentionDays()));r.getSession().setAttribute(PREVIEW,p);return p;});
    }
    public record Apply(String token,Boolean consent){}
    @PostMapping("/apply") @ResponseBody public ResponseEntity<?> apply(@RequestBody Apply v,HttpServletRequest r){
        return run(r,()->{
            var value=r.getSession().getAttribute(PREVIEW);
            if(!Boolean.TRUE.equals(v.consent())||!(value instanceof Preview p)||!p.token().equals(v.token()))throw new IllegalArgumentException("Confirmation required");
            r.getSession().removeAttribute(PREVIEW);return service.apply(session(r),p);
        });
    }
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> action){
        var s=session(r);if(s==null)return error(401,"audit.login");
        synchronized(s){try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}
            catch(IllegalArgumentException|DateTimeException ex){return error(400,"audit.invalid");}
            catch(AuditHistoryRepository.PartialChange ex){return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("audit.partial","Partial change")+" · "+ex.completed+" · "+OracleErrorDetails.forDisplay(ex)));}
            catch(RuntimeException ex){return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("audit.error","Audit request failed")+" · "+OracleErrorDetails.forDisplay(ex)));}
        }
    }
    private ResponseEntity<?> error(int code,String key){return ResponseEntity.status(code).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text(key,key)));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<?> invalid(){return error(400,"audit.invalid");}
}
