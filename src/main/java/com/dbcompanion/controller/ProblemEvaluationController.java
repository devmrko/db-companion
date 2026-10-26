package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.*;
import com.dbcompanion.service.ProblemEvaluationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-test/problems/evaluation")
public class ProblemEvaluationController {
    private final ProblemEvaluationService service;
    public ProblemEvaluationController(ProblemEvaluationService service){this.service=service;}
    public record Prepare(String parentId,String attemptId,String criteria){@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected field");}}
    public record Run(String token,boolean consent){@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected field");}}
    public record Save(String reportId,ProblemEvaluation.Verdict verdict,String reason,boolean confirmed){@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected field");}}
    @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody Prepare v,Locale locale,HttpServletRequest r){return respond(r,()->service.preview(session(r),v.parentId(),v.attemptId(),v.criteria(),locale));}
    @PostMapping("/review") public ResponseEntity<?> review(@RequestBody Run v,HttpServletRequest r){return respond(r,()->service.review(session(r),v.token(),v.consent()));}
    @PostMapping("/save") public ResponseEntity<?> save(@RequestBody Save v,HttpServletRequest r){return respond(r,()->service.save(session(r),v.reportId(),v.verdict(),v.reason(),v.confirmed()));}
    @GetMapping("/history") public ResponseEntity<?> history(@RequestParam String parentId,HttpServletRequest r){return respond(r,()->service.history(session(r),parentId));}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> respond(HttpServletRequest r,Supplier<?> action){
        if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}
        catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(Ontology.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(IllegalArgumentException ex){return error(400,UiMessages.text("evaluation.invalid","평가 기준·판정·사유를 확인해 주세요."));}
        catch(RuntimeException ex){return error(503,UiMessages.text("evaluation.storageFailed","평가 저장소를 확인하지 못했습니다. AI를 다시 호출하지 말고 저장 여부부터 확인해 주세요."));}
    }
    private ResponseEntity<?> error(int status,String text){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",text));}
}
