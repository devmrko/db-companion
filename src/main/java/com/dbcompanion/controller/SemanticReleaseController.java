package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.*;
import com.dbcompanion.service.SemanticReleaseService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/semantic-releases")
public class SemanticReleaseController {
    private final SemanticReleaseService service;
    public SemanticReleaseController(SemanticReleaseService service){this.service=service;}
    public record Create(String name,boolean confirmed){}
    public record Select(String selection,SemanticRelease.Preference expected,boolean confirmed){}
    public record Deploy(String id,SemanticRelease.Head expected,String reason,boolean confirmed){}
    public record Initialize(boolean confirmed){}
    public record Revise(String parentId,String name,List<SemanticRelease.TermRef> terms,List<SemanticRelease.SourceRef> sources,boolean confirmed){}
    @GetMapping public ResponseEntity<?> status(HttpServletRequest r){return run(r,()->service.status(session(r)));}
    @GetMapping("/catalog") public ResponseEntity<?> catalog(HttpServletRequest r){return run(r,()->service.catalog(session(r)));}
    @GetMapping("/{id}") public ResponseEntity<?> detail(@PathVariable String id,HttpServletRequest r){return run(r,()->service.detail(session(r),id));}
    @PostMapping("/create") public ResponseEntity<?> create(@RequestBody Create v,HttpServletRequest r){return run(r,()->service.create(session(r),v.name(),v.confirmed()));}
    @PostMapping("/initialize") public ResponseEntity<?> initialize(@RequestBody Initialize v,HttpServletRequest r){return run(r,()->service.initialize(session(r),v.confirmed()));}
    @PostMapping("/revise") public ResponseEntity<?> revise(@RequestBody Revise v,HttpServletRequest r){return run(r,()->service.revise(session(r),v.parentId(),v.name(),v.terms(),v.sources(),v.confirmed()));}
    @PostMapping("/select") public ResponseEntity<?> select(@RequestBody Select v,HttpServletRequest r){return run(r,()->service.select(session(r),v.selection(),v.expected(),v.confirmed()));}
    @PostMapping("/deploy") public ResponseEntity<?> deploy(@RequestBody Deploy v,HttpServletRequest r){return run(r,()->service.deploy(session(r),v.id(),v.expected(),v.reason(),v.confirmed()));}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> work){
        if(session(r)==null)return error(401,"Login required");
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(AiAssistant.Failure e){return error(e.status(),e.getMessage());}
        catch(Ontology.Failure e){return error(e.status(),e.getMessage());}
        catch(RuntimeException e){return error(503,"Semantic version operation failed. Refresh and check storage and permissions; no automatic retry.");}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<?> invalid(){return error(400,"Invalid semantic version request");}
}
