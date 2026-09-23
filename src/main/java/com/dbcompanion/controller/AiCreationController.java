package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AiCreation;
import com.dbcompanion.model.AiCreation.*;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.AiCreationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-create")
public class AiCreationController {
    private final AiCreationService service;public AiCreationController(AiCreationService service){this.service=service;}
    public record Install(String schema) {}
    public record Create(String token,@com.fasterxml.jackson.annotation.JsonProperty(required=true) boolean confirmed,@com.fasterxml.jackson.annotation.JsonProperty(required=true) boolean consent) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unknown creation field: "+key);}
    }
    public record Copy(String token,@com.fasterxml.jackson.annotation.JsonProperty(required=true) int index,@com.fasterxml.jackson.annotation.JsonProperty(required=true) boolean consent) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unknown copy field: "+key);}
    }
    @GetMapping("/form") public ResponseEntity<?> form(@RequestParam String schema,@RequestParam Kind kind,@RequestParam(defaultValue="") String source,HttpServletRequest r){return run(r,()->service.form(session(r),schema,kind,source));}
    @PostMapping("/install") public ResponseEntity<?> install(@RequestBody Install v,HttpServletRequest r){return run(r,()->{service.install(session(r),v.schema());return Map.of("ready",true);});}
    @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody Input v,HttpServletRequest r){return run(r,()->service.preview(session(r),v));}
    @PostMapping("/create") public ResponseEntity<?> create(@RequestBody Create v,HttpServletRequest r){return run(r,()->service.create(session(r),v.token(),v.confirmed(),v.consent()));}
    @PostMapping("/copy") public ResponseEntity<?> copy(@RequestBody Copy v,HttpServletRequest r){return run(r,()->service.copyNext(session(r),v.token(),v.index(),v.consent()));}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> fn){if(session(r)==null)return error(401,"Login required");try{Object result=fn.get();return ResponseEntity.status(result instanceof Result value&&!value.verified()?202:200).header("Cache-Control","no-store").body(result);}catch(MetadataEditException ex){return error(ex.status(),ex.userMessage());}catch(RuntimeException ex){return error(503,CredentialCatalogRepository.error(ex));}}
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<?> invalid(){return error(400,AiCreation.error(400,"attributesInvalid").userMessage());}
}
