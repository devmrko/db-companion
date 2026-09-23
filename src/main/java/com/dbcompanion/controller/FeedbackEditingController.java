package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.FeedbackEditing.*;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.FeedbackEditingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/ai-feedback/edit")
public class FeedbackEditingController {
    private final FeedbackEditingService service;public FeedbackEditingController(FeedbackEditingService service){this.service=service;}
    @GetMapping public ResponseEntity<?> form(@RequestParam String schema,@RequestParam String profile,@RequestParam(defaultValue="") String id,HttpServletRequest r){return run(r,()->service.form(session(r),schema,profile,id));}
    @PostMapping public ResponseEntity<?> save(@RequestBody Save v,HttpServletRequest r){return run(r,()->service.save(session(r),v));}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> fn){if(session(r)==null)return error(401,"Login required");try{var v=fn.get();return ResponseEntity.status(v instanceof Result res&&!res.verified()?202:200).header("Cache-Control","no-store").body(v);}catch(MetadataEditException ex){return error(ex.status(),ex.userMessage());}catch(RuntimeException ex){return error(503,CredentialCatalogRepository.error(ex));}}
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
}
