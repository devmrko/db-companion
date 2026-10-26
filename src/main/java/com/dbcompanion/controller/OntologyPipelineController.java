package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.OntologyPipelineService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ontology/pipeline")
public class OntologyPipelineController {
    private final OntologyPipelineService service;public OntologyPipelineController(OntologyPipelineService service){this.service=service;}
    public record Scope(String schema,List<String> tables){} public record Step(String token,int index,boolean consent,Boolean all){public Step(String token,int index,boolean consent){this(token,index,consent,false);}} public record Stop(String token){}
    public record Saved(String schema,String before){} public record Restore(String schema,String token){} public record Call(String schema,String token,int index,boolean confirmed){}
    public record Payload(String token,int index){}
    public record Graph(String schema,String name,List<String> relations){} public record Create(String token,boolean confirmed){}
    public record Status(com.dbcompanion.service.OntologyDiscovery.Preview plan){}
    @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody Scope v,HttpServletRequest r){return run(r,false,()->service.preview(session(r),v.schema(),v.tables()));}
    @PostMapping("/status") public ResponseEntity<?> status(@RequestBody Scope v,HttpServletRequest r){return run(r,false,()->new Status(service.status(session(r),v.schema())));}
    @PostMapping("/payload") public ResponseEntity<?> payload(@RequestBody Payload v,HttpServletRequest r){return run(r,false,()->service.payload(session(r),v.token(),v.index()));}
    @PostMapping("/resume") public ResponseEntity<?> resume(@RequestBody Step v,HttpServletRequest r){return run(r,false,()->service.resume(session(r),v.token(),v.index(),v.consent(),Boolean.TRUE.equals(v.all())));}
    @PostMapping("/saved") public ResponseEntity<?> saved(@RequestBody Saved v,HttpServletRequest r){return run(r,false,()->service.saved(session(r),v.schema(),Objects.toString(v.before(),"")));}
    @PostMapping("/restore") public ResponseEntity<?> restore(@RequestBody Restore v,HttpServletRequest r){return run(r,false,()->service.restore(session(r),v.schema(),v.token()));}
    @PostMapping("/calls") public ResponseEntity<?> calls(@RequestBody Restore v,HttpServletRequest r){return run(r,false,()->service.calls(session(r),v.schema(),v.token()));}
    @PostMapping("/call") public ResponseEntity<?> call(@RequestBody Call v,HttpServletRequest r){return run(r,false,()->service.call(session(r),v.schema(),v.token(),v.index()));}
    @PostMapping("/recover") public ResponseEntity<?> recover(@RequestBody Call v,HttpServletRequest r){return run(r,false,()->service.recover(session(r),v.schema(),v.token(),v.index(),v.confirmed()));}
    @PostMapping("/generate") public ResponseEntity<?> generate(@RequestBody Step v,Locale locale,HttpServletRequest r){return run(r,false,()->service.generate(session(r),v.token(),v.index(),v.consent(),locale));}
    @PostMapping("/stop") public ResponseEntity<?> stop(@RequestBody Stop v,HttpServletRequest r){return run(r,false,()->{service.stop(session(r),v.token());return Map.of("stopped",true);});}
    @PostMapping("/graph/preview") public ResponseEntity<?> graph(@RequestBody Graph v,HttpServletRequest r){return run(r,false,()->service.graphPreview(session(r),v.schema(),v.name(),v.relations()));}
    @PostMapping("/graph/create") public ResponseEntity<?> create(@RequestBody Create v,HttpServletRequest r){return run(r,true,()->service.create(session(r),v.token(),v.confirmed()));}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> run(HttpServletRequest r,boolean ddl,Supplier<?> action){
        if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","Login required"));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}
        catch(com.dbcompanion.service.OntologyDiscovery.ResponseFailure e){return ResponseEntity.status(e.status()).header("Cache-Control","no-store").body(Map.of("error",e.getMessage(),"diagnostic",e.diagnostic()));}
        catch(Ontology.Failure e){return error(e.status(),e.getMessage());}
        catch(com.dbcompanion.model.AiAssistant.Failure e){return error(e.status(),e.getMessage());}
        catch(RuntimeException e){return error(503,UiMessages.text(ddl?"ontology.pg.createError":"ontology.discovery.error","Request failed")+" · "+CredentialCatalogRepository.error(e));}
    }
    private ResponseEntity<?> error(int status,String error){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",error));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<?> invalid(){return error(400,UiMessages.text("ontology.invalid","Invalid input"));}
}
