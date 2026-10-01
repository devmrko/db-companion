package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.service.NativeMetadataService;
import com.dbcompanion.service.OntologyArchiveService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ontology/native")
public class NativeMetadataController {
    private final NativeMetadataService service;
    public NativeMetadataController(NativeMetadataService service){this.service=service;}
    public record Change(String schema,String table,Boolean confirmed) { }
    public record Scope(String schema,List<String> tables) { }
    public record Save(String schema,String token,List<String> ids,Boolean confirmed) { }
    @GetMapping("/status") public ResponseEntity<?> status(@RequestParam String schema,HttpServletRequest r){return run(r,()->service.status(session(r),schema));}
    @GetMapping("/scripts") public ResponseEntity<?> scripts(@RequestParam String schema,HttpServletRequest r){return run(r,()->service.scripts(session(r),schema));}
    @GetMapping("/columns") public ResponseEntity<?> columns(@RequestParam String schema,@RequestParam String table,HttpServletRequest r){return run(r,()->service.columns(session(r),schema,table));}
    @PostMapping("/install") public ResponseEntity<?> install(@RequestBody Change v,HttpServletRequest r){return run(r,()->service.install(session(r),v.schema(),Boolean.TRUE.equals(v.confirmed())));}
    @PostMapping("/capture") public ResponseEntity<?> capture(@RequestBody Change v,HttpServletRequest r){return run(r,()->service.capture(session(r),v.schema(),v.table(),Boolean.TRUE.equals(v.confirmed())));}
    @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody Scope v,HttpServletRequest r){return run(r,()->service.preview(session(r),v.schema(),v.tables()));}
    @PostMapping("/save") public ResponseEntity<?> save(@RequestBody Save v,HttpServletRequest r){return run(r,()->service.save(session(r),v.schema(),v.token(),v.ids(),Boolean.TRUE.equals(v.confirmed())));}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> action){
        if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","Login required"));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}
        catch(Ontology.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(RuntimeException ex){return error(503,UiMessages.text("ontology.native.error","Oracle RDF request failed")+" · "+OntologyArchiveService.detail(ex));}
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalid(){return error(400,UiMessages.text("ontology.invalid","Invalid request"));}
}
