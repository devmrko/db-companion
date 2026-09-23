package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.OntologyScopeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ontology/pipeline/scope")
public class OntologyScopeController {
    private final OntologyScopeService service;public OntologyScopeController(OntologyScopeService service){this.service=service;}
    public record Scope(String schema){} public record Profile(String schema,String profile){} public record Page(String schema,String before){}
    public record Save(String schema,String name,List<String> tables){} public record Install(String schema,boolean confirmed){}
    @PostMapping("/options") public ResponseEntity<?> options(@RequestBody Scope v,HttpServletRequest r){return run(r,()->service.options(session(r),v.schema()));}
    @PostMapping("/profile") public ResponseEntity<?> profile(@RequestBody Profile v,HttpServletRequest r){return run(r,()->service.fromProfile(session(r),v.schema(),v.profile()));}
    @PostMapping("/saved") public ResponseEntity<?> saved(@RequestBody Page v,HttpServletRequest r){return run(r,()->service.page(session(r),v.schema(),v.before()));}
    @PostMapping("/save") public ResponseEntity<?> save(@RequestBody Save v,HttpServletRequest r){return run(r,()->Map.of("id",service.save(session(r),v.schema(),v.name(),v.tables())));}
    @PostMapping("/install") public ResponseEntity<?> install(@RequestBody Install v,HttpServletRequest r){return run(r,()->service.install(session(r),v.schema(),v.confirmed()));}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> action){
        if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","Login required"));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}
        catch(Ontology.Failure e){return error(e.status(),e.getMessage());}
        catch(AppException e){return error(403,e.userMessage());}
        catch(RuntimeException e){return error(503,UiMessages.text("ontology.scope.error","Scope request failed")+" · "+CredentialCatalogRepository.error(e));}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<?> invalid(){return error(400,UiMessages.text("ontology.invalid","Invalid input"));}
}
