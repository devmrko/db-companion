package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.OntologyQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/ontology-query")
public class OntologyQueryController {
    private final OntologyQueryService service;public OntologyQueryController(OntologyQueryService service){this.service=service;}
    @GetMapping public String page(HttpServletRequest r,Model m){var s=session(r);if(s==null)return "redirect:/login";synchronized(s){m.addAttribute("info",s.metadata().info());m.addAttribute("schemas",s.metadata().schemas());m.addAttribute("selectedSchema",s.metadata().selectedSchema());}m.addAttribute("activePage","ontology-query");m.addAttribute("languageReturn","/ontology-query");return "ontology-query";}
    @GetMapping("/options") @ResponseBody public ResponseEntity<?> options(@RequestParam String schema,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest r){return run(r,"loadError",()->service.options(session(r),schema,refresh));}
    public record Search(String schema,String question,String anchor){}
    public record Prepare(String id,String mode,String route){}
    public record Generate(String token,boolean consent){}
    public record Execute(String token,boolean confirmed){}
    public record Cancel(String token){}
    @PostMapping("/search") @ResponseBody public ResponseEntity<?> search(@RequestBody Search v,@RequestParam(defaultValue="") String language,Locale locale,HttpServletRequest r){return run(r,"loadError",()->service.search(session(r),v.schema(),v.question(),v.anchor(),QuestionAnalysisController.language(language,locale)));}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody Prepare v,Locale locale,HttpServletRequest r){return run(r,"loadError",()->service.preview(session(r),v.id(),v.mode(),v.route(),locale));}
    @PostMapping("/generate") @ResponseBody public ResponseEntity<?> generate(@RequestBody Generate v,HttpServletRequest r){return run(r,"callError",()->service.generate(session(r),v.token(),v.consent()));}
    @PostMapping("/execute") @ResponseBody public ResponseEntity<?> execute(@RequestBody Execute v,HttpServletRequest r){return run(r,"executeError",()->service.execute(session(r),v.token(),v.confirmed()));}
    @PostMapping("/cancel") @ResponseBody public ResponseEntity<?> cancel(@RequestBody Cancel v,HttpServletRequest r){return run(r,"loadError",()->{service.cancel(session(r),v.token());return Map.of("cancelled",true);});}
    @PostMapping("/invalidate") @ResponseBody public ResponseEntity<?> invalidate(HttpServletRequest r){return run(r,"loadError",()->{service.invalidate(session(r));return Map.of("invalidated",true);});}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> run(HttpServletRequest r,String key,Supplier<?> fn){if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","Login required"));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(fn.get());}catch(Ontology.Failure ex){return error(ex.status(),ex.getMessage());}catch(com.dbcompanion.model.AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}catch(RuntimeException ex){return error(503,UiMessages.text("ontology.query."+key,key)+" · "+CredentialCatalogRepository.error(ex));}}
    private ResponseEntity<?> error(int status,String text){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",text));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) @ResponseBody public ResponseEntity<?> invalid(){return error(400,UiMessages.text("ontology.query.invalid","Invalid request"));}
}
