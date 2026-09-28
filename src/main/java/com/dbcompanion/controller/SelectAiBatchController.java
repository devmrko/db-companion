package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.service.SelectAiBatchService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/ai-test/batch")
public class SelectAiBatchController {
 private final SelectAiBatchService service; public SelectAiBatchController(SelectAiBatchService service){this.service=service;}
 public record Prepare(List<String> parentIds,String profile){@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected request field");}}
 public record Run(String generation,String token,boolean consent){@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected request field");}}
 public record Save(String generation,String token,boolean confirmed){@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected request field");}}
 @PostMapping("/prepare") ResponseEntity<?> prepare(@RequestBody Prepare v,HttpServletRequest r){return run(r,()->service.prepare(session(r),v.parentIds(),v.profile()));}
 @PostMapping("/next") ResponseEntity<?> next(@RequestBody Run v,HttpServletRequest r){return run(r,()->service.run(session(r),v.generation(),v.token(),v.consent()));}
 @PostMapping("/cancel") ResponseEntity<?> cancel(@RequestBody Run v,HttpServletRequest r){return run(r,()->service.cancel(session(r),v.generation()));}
 @PostMapping("/save") ResponseEntity<?> save(@RequestBody Save v,HttpServletRequest r){return run(r,()->Map.of("id",service.save(session(r),v.generation(),v.token(),v.confirmed())));}
 @GetMapping("/status") ResponseEntity<?> status(HttpServletRequest r){return run(r,()->Collections.singletonMap("plan",service.status(session(r))));}
 private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
 private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> f){if(session(r)==null)return ResponseEntity.status(401).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다. 다시 로그인해 주세요.")));try{return ResponseEntity.ok().header("Cache-Control","no-store").body(f.get());}catch(AiAssistant.Failure e){return ResponseEntity.status(e.status()).header("Cache-Control","no-store").body(Map.of("error",e.getMessage()));}catch(Ontology.Failure e){return ResponseEntity.status(e.status()).header("Cache-Control","no-store").body(Map.of("error",e.getMessage()));}catch(IllegalArgumentException e){return ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("assistant.invalid","요청 정보를 확인해 주세요.")));}catch(RuntimeException e){return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("aitest.loadError","요청을 확인하지 못했습니다.")));}}
}
