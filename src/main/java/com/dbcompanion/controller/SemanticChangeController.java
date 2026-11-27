package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.SemanticChange;
import com.dbcompanion.service.SemanticChangeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/semantic-history")
public class SemanticChangeController {
    private final SemanticChangeService service;
    public SemanticChangeController(SemanticChangeService service){this.service=service;}
    @GetMapping("/status") public ResponseEntity<?> status(@RequestParam String schema,@RequestParam String kind,HttpServletRequest r){return call(r,s->service.status(s,schema,kind));}
    public record Install(String schema,String kind,boolean consent) {}
    @PostMapping("/install") public ResponseEntity<?> install(@RequestBody Install input,HttpServletRequest r){return call(r,s->service.install(s,input.schema(),input.kind(),input.consent()));}
    @GetMapping public ResponseEntity<?> page(@RequestParam String schema,@RequestParam String kind,@RequestParam(defaultValue="") String name,HttpServletRequest r){return call(r,s->service.page(s,schema,kind,name));}
    @GetMapping("/detail") public ResponseEntity<?> detail(@RequestParam String schema,@RequestParam String kind,@RequestParam String id,HttpServletRequest r){return call(r,s->service.detail(s,schema,kind,id));}
    private ResponseEntity<?> call(HttpServletRequest r,Function<PoolSession,Object> action){
        var http=r.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return ResponseEntity.status(401).body(Map.of("error","Session expired"));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.apply(session));}
        catch(MetadataEditException ex){return ResponseEntity.status(ex.status()).header("Cache-Control","no-store").body(Map.of("error",ex.userMessage()));}
        catch(com.dbcompanion.model.Ontology.Failure ex){return ResponseEntity.status(ex.status()).header("Cache-Control","no-store").body(Map.of("error",ex.getMessage()));}
        catch(RuntimeException ex){return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",SemanticChange.error(503,"unavailable").userMessage()));}
    }
}
