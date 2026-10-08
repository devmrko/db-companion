package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.repository.MetadataGraphRepository;
import com.dbcompanion.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ontology/metadata-graph")
public class MetadataGraphController {
    private final MetadataGraphService service;
    public MetadataGraphController(MetadataGraphService service){this.service=service;}
    public record Request(String schema,String name,String token,Boolean confirmed,List<String> relations) { }
    @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody Request v,HttpServletRequest r){return run(r,()->service.preview(session(r),v.schema(),v.name(),v.relations()));}
    @PostMapping("/create") public ResponseEntity<?> create(@RequestBody Request v,HttpServletRequest r){return run(r,()->service.create(session(r),v.schema(),v.token(),Boolean.TRUE.equals(v.confirmed())));}
    @PostMapping("/list") public ResponseEntity<?> existing(@RequestBody Request v,HttpServletRequest r){return run(r,()->service.existing(session(r),v.schema()));}
    @PostMapping("/query") public ResponseEntity<?> query(@RequestBody Request v,HttpServletRequest r){return run(r,()->service.query(session(r),v.schema(),v.name()));}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> error(int status,String text){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",text));}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> action){
        if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","Login required"));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}
        catch(MetadataGraphRepository.CreationFailure ex){return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",ex.getMessage()+" · "+String.join(", ",ex.completed())+" · "+OntologyArchiveService.detail(ex),"completed",ex.completed()));}
        catch(Ontology.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(RuntimeException ex){return error(503,UiMessages.text("ontology.mg.error","Metadata graph request failed")+" · "+OntologyArchiveService.detail(ex));}
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalid(){return error(400,UiMessages.text("ontology.invalid","Invalid request"));}
}
