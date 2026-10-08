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
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(OntologyQueryController.class);
    private final OntologyQueryService service;public OntologyQueryController(OntologyQueryService service){this.service=service;}
    @GetMapping public String page(HttpServletRequest r,Model m){var s=session(r);if(s==null)return "redirect:/login";synchronized(s){m.addAttribute("info",s.metadata().info());m.addAttribute("schemas",s.metadata().schemas());m.addAttribute("selectedSchema",s.metadata().selectedSchema());}m.addAttribute("activePage","ontology-query");m.addAttribute("languageReturn","/ontology-query");return "ontology-query";}
    @GetMapping("/options") @ResponseBody public ResponseEntity<?> options(@RequestParam String schema,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest r){return run(r,"loadError",()->service.options(session(r),schema,refresh));}
    public record Search(String schema,String question,String anchor){}
    public record Grounded(String schema,String question,String anchor,String dictionaryId,List<String> termIds,String graph){}
    @PostMapping("/ai-interpret-preview") @ResponseBody public ResponseEntity<?> aiInterpret(@RequestBody Grounded v,@RequestParam(defaultValue="") String language,Locale locale,HttpServletRequest r){return run(r,"loadError",()->service.interpretPreview(session(r),v.schema(),v.question(),v.anchor(),QuestionAnalysisController.language(language,locale),v.dictionaryId(),v.termIds(),v.graph()));}
    @PostMapping("/ai-recommend-preview") @ResponseBody public ResponseEntity<?> aiRecommend(@RequestBody Prepare v,Locale locale,HttpServletRequest r){return run(r,"loadError",()->service.recommendPreview(session(r),v.id(),locale));}
    @PostMapping("/assistant-generate") @ResponseBody public ResponseEntity<?> assistantGenerate(@RequestBody Generate v,HttpServletRequest r){return run(r,"callError",()->service.assistantGenerate(session(r),v.token(),v.consent()));}
    @GetMapping("/graphs") @ResponseBody public ResponseEntity<?> graphs(@RequestParam String schema,HttpServletRequest r){return run(r,"loadError",()->service.graphOptions(session(r),schema));}
    @PostMapping("/interpret") @ResponseBody public ResponseEntity<?> interpret(@RequestBody Search v,HttpServletRequest r){return run(r,"loadError",()->service.interpret(session(r),v.schema(),v.question()));}
    @PostMapping("/grounded-search") @ResponseBody public ResponseEntity<?> grounded(@RequestBody Grounded v,@RequestParam(defaultValue="") String language,Locale locale,HttpServletRequest r){return run(r,"loadError",()->service.groundedSearch(session(r),v.schema(),v.question(),v.anchor(),QuestionAnalysisController.language(language,locale),v.dictionaryId(),v.termIds(),v.graph()));}
    public record Prepare(String id,String mode,String route){}
    public record PlanSelection(String id,String mode,String route,List<String> tables,List<String> candidates){}
    public record RdfSelection(String id,String mode,List<String> tables,List<String> relations){}
    @PostMapping("/test-evidence") @ResponseBody public ResponseEntity<?> testEvidence(@RequestBody RdfSelection v,HttpServletRequest r){return run(r,"loadError",()->service.testEvidence(session(r),v.id(),v.mode(),v.tables(),v.relations()));}
    @PostMapping("/rdf-selection") @ResponseBody public ResponseEntity<?> rdfSelection(@RequestBody RdfSelection v,HttpServletRequest r){return run(r,"loadError",()->service.selectRdf(session(r),v.id(),v.mode(),v.tables(),v.relations()));}
    @PostMapping("/plan-selection") @ResponseBody public ResponseEntity<?> planSelection(@RequestBody PlanSelection v,HttpServletRequest r){return run(r,"loadError",()->service.applyPlan(session(r),v.id(),v.mode(),v.route(),v.tables(),v.candidates()));}
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
    private ResponseEntity<?> error(int status,String text){if(status==422)LOG.warn("Ontology response validation failed: {}",text);return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",text));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) @ResponseBody public ResponseEntity<?> invalid(){return error(400,UiMessages.text("ontology.query.invalid","Invalid request"));}
}
