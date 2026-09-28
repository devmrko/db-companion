package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.*;
import com.dbcompanion.service.ProblemQuestionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller @RequestMapping("/ai-test/problems")
public class ProblemQuestionController {
    private final ProblemQuestionService service; public ProblemQuestionController(ProblemQuestionService service){this.service=service;}
    @GetMapping public String page(HttpServletRequest r,Model model){if(session(r)==null)return "redirect:/login";model.addAttribute("info",session(r).metadata().info());model.addAttribute("activePage","ai-problems");model.addAttribute("languageReturn","/ai-test/problems");return "ai-problems";}
    public record Save(String question,String description,String expected,String expectedSql,ProblemQuestion.Status status){@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String name,Object value){throw new IllegalArgumentException("Unexpected request field");}}
    public record Attempt(String parentId,String input,String conditions,String response,String sql,String error,String profile,String model,String options,String prompt,String metadata,String feedback,String snapshotKind,String availability,long elapsedMillis){@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String name,Object value){throw new IllegalArgumentException("Unexpected request field");}}
    public record Remove(List<String> ids,boolean confirmed,int records,int attempts,String fingerprint){@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String name,Object value){throw new IllegalArgumentException("Unexpected request field");}}
    public record Compare(String parentId,String leftId,String rightId){@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String name,Object value){throw new IllegalArgumentException("Unexpected request field");}}
    public record Update(ProblemQuestion.Status status,boolean retained,String expectedUpdatedAt){@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String name,Object value){throw new IllegalArgumentException("Unexpected request field");}}
    @GetMapping("/status") @ResponseBody public ResponseEntity<?> status(HttpServletRequest r){return run(r,()->Map.of("storage",service.status(session(r))));}
    @GetMapping("/list") @ResponseBody public ResponseEntity<?> list(@RequestParam(defaultValue="") String before,HttpServletRequest r){return run(r,()->service.page(session(r),before));}
    @GetMapping("/detail") @ResponseBody public ResponseEntity<?> detail(@RequestParam String id,HttpServletRequest r){return run(r,()->service.detail(session(r),id));}
    @PostMapping @ResponseBody public ResponseEntity<?> save(@RequestBody Save v,HttpServletRequest r){return run(r,()->Map.of("id",service.save(session(r),new ProblemQuestion.Create(v.question(),v.description(),v.expected(),v.expectedSql(),v.status()))));}
    @PostMapping("/attempt") @ResponseBody public ResponseEntity<?> attempt(@RequestBody Attempt v,HttpServletRequest r){return run(r,()->Map.of("id",service.saveAttempt(session(r),new ProblemQuestion.Attempt(1,"",v.parentId(),v.input(),v.conditions(),v.response(),v.sql(),v.error(),v.profile(),v.model(),v.options(),v.prompt(),v.metadata(),v.feedback(),v.snapshotKind(),v.availability(),null,v.elapsedMillis(),null,null,null,null))));}
    @PostMapping("/compare") @ResponseBody public ResponseEntity<?> compare(@RequestBody Compare v,HttpServletRequest r){return run(r,()->service.compare(session(r),v.parentId(),v.leftId(),v.rightId()));}
    @PostMapping("/update") @ResponseBody public ResponseEntity<?> update(@RequestParam String id,@RequestBody Update v,HttpServletRequest r){return run(r,()->service.update(session(r),id,new ProblemQuestion.Update(v.status(),v.retained()),java.time.Instant.parse(v.expectedUpdatedAt())));}
    @PostMapping("/delete-preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody Remove v,HttpServletRequest r){return run(r,()->service.deletePreview(session(r),v.ids()));}
    @PostMapping("/delete") @ResponseBody public ResponseEntity<?> delete(@RequestBody Remove v,HttpServletRequest r){return run(r,()->Map.of("deleted",service.delete(session(r),v.ids(),v.records(),v.attempts(),v.fingerprint(),v.confirmed())));}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> work){if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}catch(Ontology.Failure ex){return error(ex.status(),ex.getMessage());}catch(IllegalArgumentException ex){return error(400,UiMessages.text("assistant.invalid","요청 정보를 확인해 주세요."));}catch(RuntimeException ex){return error(503,UiMessages.text("problemQuestion.error","문제 질문 저장소를 확인하지 못했습니다."));}}
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
}
