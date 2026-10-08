package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.*;
import com.dbcompanion.service.OntologyWizardService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/ontology/wizard")
public class OntologyWizardController {
    private final OntologyWizardService service;
    public OntologyWizardController(OntologyWizardService service){this.service=service;}
    public record SampleRequest(String schema,String table,int revision,List<String> columns,int count,boolean confirmed,Boolean statistics){}
    public record Generate(String token,boolean consent){}
    public record Apply(String schema,String table,int revision,String token,List<OntologyWizard.Edit> edits){}
    public record Cancel(String token){}
    @GetMapping("/options") @ResponseBody public ResponseEntity<?> options(@RequestParam String schema,@RequestParam String table,@RequestParam int revision,HttpServletRequest r){return run(r,()->service.options(session(r),schema,table,revision));}
    @PostMapping("/sample") @ResponseBody public ResponseEntity<?> sample(@RequestBody SampleRequest v,Locale locale,HttpServletRequest r){return run(r,()->service.sample(session(r),v.schema(),v.table(),v.revision(),v.columns(),v.count(),v.confirmed(),Boolean.TRUE.equals(v.statistics()),locale));}
    @PostMapping("/generate") @ResponseBody public ResponseEntity<?> generate(@RequestBody Generate v,HttpServletRequest r){return run(r,()->service.generate(session(r),v.token(),v.consent()));}
    @PostMapping("/apply") @ResponseBody public ResponseEntity<?> apply(@RequestBody Apply v,HttpServletRequest r){return run(r,()->service.apply(session(r),v.schema(),v.table(),v.revision(),v.token(),v.edits()));}
    @PostMapping("/cancel") @ResponseBody public ResponseEntity<?> cancel(@RequestBody Cancel v,HttpServletRequest r){return run(r,()->{service.cancel(session(r),v.token());return Map.of("cancelled",true);});}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> work){
        if(session(r)==null)return error(401,UiMessages.text("ontology.wizard.login","Login required"));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(Ontology.Failure e){return error(e.status(),e.getMessage());}
        catch(AiAssistant.Failure e){return error(e.status(),e.getMessage());}
        catch(org.springframework.dao.DuplicateKeyException e){return error(409,UiMessages.text("ontology.stale","stale"));}
        // Do not return JDBC/provider errors that could echo sample values or the generated prompt.
        catch(RuntimeException e){
            String code="";for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql){code=" · Oracle code="+sql.getErrorCode();break;}
            return error(503,UiMessages.text("ontology.wizard.failed","Operation did not complete. No automatic retry.")+code);
        }
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseBody public ResponseEntity<?> invalid(){return error(400,UiMessages.text("ontology.invalid","invalid"));}
}
