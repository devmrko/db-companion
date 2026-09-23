package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.OntologyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class OntologyController {
    private final OntologyService service;private final com.dbcompanion.service.OntologyValuesService values;
    public OntologyController(OntologyService service,com.dbcompanion.service.OntologyValuesService values){this.service=service;this.values=values;}
    @PostMapping("/ontology/values/lookup") @ResponseBody public ResponseEntity<?> values(@RequestBody com.dbcompanion.model.OntologyValues.Lookup v,HttpServletRequest r){return run(r,"loadError",()->values.lookup(session(r),v));}
    @GetMapping("/ontology") public String page(HttpServletRequest request,Model model){var s=session(request);if(s==null)return "redirect:/login";synchronized(s){var m=s.metadata();model.addAttribute("info",m.info());model.addAttribute("schemas",m.schemas());model.addAttribute("selectedSchema",m.selectedSchema());}model.addAttribute("activePage","ontology");model.addAttribute("languageReturn","/ontology");return "ontology";}
    @GetMapping("/ontology/catalog") @ResponseBody public ResponseEntity<?> catalog(@RequestParam String schema,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest r){return run(r,"loadError",()->service.catalog(session(r),schema,refresh));}
    @GetMapping("/ontology/graph") @ResponseBody public ResponseEntity<?> graph(@RequestParam String schema,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest r){return run(r,"loadError",()->service.graph(session(r),schema,refresh));}
    @GetMapping("/ontology/relationships") @ResponseBody public ResponseEntity<?> relationships(@RequestParam String schema,HttpServletRequest r){return run(r,"loadError",()->service.relationships(session(r),schema));}
    @PostMapping("/ontology/relationships/review") @ResponseBody public ResponseEntity<?> reviewRelationship(@RequestBody com.dbcompanion.model.OntologyRelations.Review v,HttpServletRequest r){return run(r,"saveError",()->service.reviewRelationship(session(r),v));}
    @GetMapping("/ontology/detail") @ResponseBody public ResponseEntity<?> detail(@RequestParam String schema,@RequestParam String table,@RequestParam(defaultValue="0") int revision,HttpServletRequest r){return run(r,"loadError",()->service.detail(session(r),schema,table,revision));}
    @GetMapping("/ontology/history") @ResponseBody public ResponseEntity<?> history(@RequestParam String schema,@RequestParam String table,@RequestParam(defaultValue="") String before,HttpServletRequest r){return run(r,"loadError",()->service.history(session(r),schema,table,before));}
    @GetMapping("/ontology/rdf") @ResponseBody public ResponseEntity<?> rdf(@RequestParam String schema,@RequestParam String table,@RequestParam int revision,HttpServletRequest r){return run(r,"loadError",()->service.rdfExport(session(r),schema,table,revision));}
    public record Install(String schema,boolean confirmed){}
    public record Capture(String schema,String table){}
    public record CaptureMissing(String schema,String table,boolean confirmed){}
    public record PreviewRequest(String schema,String table,int revision){}
    public record Save(String schema,String table,int revision,Meaning meaning,String state){}
    public record Generate(String token,boolean consent){}
    public record Apply(String schema,String table,int revision,String token,List<com.dbcompanion.model.OntologyAnalysis.Edit> edits){}
    public record Cancel(String token){}
    @PostMapping("/ontology/install") @ResponseBody public ResponseEntity<?> install(@RequestBody Install v,HttpServletRequest r){return run(r,"installError",()->service.install(session(r),v.schema(),v.confirmed()));}
    @PostMapping("/ontology/capture") @ResponseBody public ResponseEntity<?> capture(@RequestBody Capture v,HttpServletRequest r){return run(r,"saveError",()->service.capture(session(r),v.schema(),v.table()));}
    @PostMapping("/ontology/capture/missing") @ResponseBody public ResponseEntity<?> captureMissing(@RequestBody CaptureMissing v,HttpServletRequest r){return run(r,"saveError",()->service.captureMissing(session(r),v.schema(),v.table(),v.confirmed()));}
    @PostMapping("/ontology/save") @ResponseBody public ResponseEntity<?> save(@RequestBody Save v,HttpServletRequest r){return run(r,"saveError",()->service.save(session(r),v.schema(),v.table(),v.revision(),v.meaning(),v.state()));}
    @PostMapping("/ontology/ai/preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody PreviewRequest v,Locale locale,HttpServletRequest r){return run(r,"loadError",()->service.preview(session(r),v.schema(),v.table(),v.revision(),locale));}
    @PostMapping("/ontology/ai/generate") @ResponseBody public ResponseEntity<?> generate(@RequestBody Generate v,HttpServletRequest r){return run(r,"aiError",()->service.suggest(session(r),v.token(),v.consent()));}
    @PostMapping("/ontology/ai/apply") @ResponseBody public ResponseEntity<?> apply(@RequestBody Apply v,HttpServletRequest r){return run(r,"saveError",()->service.apply(session(r),v.schema(),v.table(),v.revision(),v.token(),v.edits()));}
    @PostMapping("/ontology/ai/cancel") @ResponseBody public ResponseEntity<?> cancel(@RequestBody Cancel v,HttpServletRequest r){return run(r,"aiError",()->{service.cancel(session(r),v.token());return Map.of("cancelled",true);});}
    private ResponseEntity<?> run(HttpServletRequest r,String key,Supplier<?> work){
        if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(com.dbcompanion.service.OntologyContext.ResponseFailure ex){return ResponseEntity.status(ex.status()).header("Cache-Control","no-store").body(Map.of("error",ex.getMessage(),"diagnostic",ex.diagnostic()));}
        catch(Failure ex){return error(ex.status(),ex.getMessage());}
        catch(com.dbcompanion.model.AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(org.springframework.dao.DuplicateKeyException ex){return error(409,UiMessages.text("ontology.stale","stale"));}
        catch(RuntimeException ex){return error(503,UiMessages.text("ontology."+key,key)+" · "+CredentialCatalogRepository.error(ex));}
    }
    private ResponseEntity<?> error(int code,String value){return ResponseEntity.status(code).header("Cache-Control","no-store").body(Map.of("error",value));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseBody public ResponseEntity<?> invalidRequest(){return error(400,UiMessages.text("ontology.invalid","invalid"));}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
}
