package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.SelectAiTest.Action;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.SelectAiTestService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SelectAiTestController {
    private final SelectAiTestService service;
    public SelectAiTestController(SelectAiTestService service){this.service=service;}
    @GetMapping("/ai-test")
    public String page(HttpServletRequest request,Model model){
        var session=session(request);if(session==null)return "redirect:/login";
        model.addAttribute("info",session.metadata().info());model.addAttribute("activePage","ai-test");
        model.addAttribute("languageReturn","/ai-test");return "ai-test";
    }
    @GetMapping("/ai-test/options") @ResponseBody
    public ResponseEntity<?> options(@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest r){return respond(r,()->service.options(session(r),refresh));}
    public record Choose(String name) {}
    @PostMapping("/ai-test/selection") @ResponseBody
    public ResponseEntity<?> selection(@RequestBody Choose v,HttpServletRequest r){return respond(r,()->{service.select(session(r),v.name());return Map.of("saved",true);});}
    @GetMapping("/ai-test/profile") @ResponseBody
    public ResponseEntity<?> profile(HttpServletRequest r){return respond(r,()->service.detail(session(r)));}
    @GetMapping("/ai-test/evidence/options") @ResponseBody
    public ResponseEntity<?> evidenceOptions(@RequestParam String schema,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest r){return respond(r,()->service.evidenceOptions(session(r),schema,refresh));}
    public record EvidenceSearch(String schema,String question,String anchor) {}
    @PostMapping("/ai-test/evidence/search") @ResponseBody
    public ResponseEntity<?> evidenceSearch(@RequestBody EvidenceSearch v,HttpServletRequest r){return respond(r,()->service.evidenceSearch(session(r),v.schema(),v.question(),v.anchor()));}
    public record EvidenceChoose(String searchId,String route) {}
    @PostMapping("/ai-test/evidence/choose") @ResponseBody
    public ResponseEntity<?> evidenceChoose(@RequestBody EvidenceChoose v,HttpServletRequest r){return respond(r,()->service.evidenceChoose(session(r),v.searchId(),v.route()));}
    public record Prepare(Action action,String question,boolean useOntology,String evidenceHash) {}
    @PostMapping("/ai-test/preview") @ResponseBody
    public ResponseEntity<?> preview(@RequestBody Prepare v,Locale locale,HttpServletRequest r){return respond(r,()->service.preview(session(r),v.action(),v.question(),v.useOntology(),v.evidenceHash(),locale));}
    public record Run(String token,boolean consent) {}
    public record ReviewPrepare(String promptId) {}
    @PostMapping("/ai-test/review/preview") @ResponseBody
    public ResponseEntity<?> reviewPreview(@RequestBody ReviewPrepare v,Locale locale,HttpServletRequest r){return respond(r,()->service.reviewPreview(session(r),v.promptId(),locale));}
    @PostMapping("/ai-test/review") @ResponseBody
    public ResponseEntity<?> review(@RequestBody Run v,HttpServletRequest r){return respond(r,()->service.review(session(r),v.token(),v.consent()));}
    @PostMapping("/ai-test/generate") @ResponseBody
    public ResponseEntity<?> generate(@RequestBody Run v,HttpServletRequest r){return respond(r,()->service.run(session(r),v.token(),v.consent()));}
    public record Cancel(String token) {}
    public record ExecutionPrepare(String resultId) {}
    @PostMapping("/ai-test/execute/preview") @ResponseBody
    public ResponseEntity<?> executionPreview(@RequestBody ExecutionPrepare v,HttpServletRequest r){return respond(r,()->service.executionPreview(session(r),v.resultId()));}
    @PostMapping("/ai-test/execute") @ResponseBody
    public ResponseEntity<?> execute(@RequestBody Run v,HttpServletRequest r){return respond(r,()->service.execute(session(r),v.token(),v.consent()));}
    @PostMapping("/ai-test/cancel") @ResponseBody
    public ResponseEntity<?> cancel(@RequestBody Cancel v,HttpServletRequest r){return respond(r,()->{service.cancel(session(r),v.token());return Map.of("cancelled",true);});}
    private ResponseEntity<?> respond(HttpServletRequest r,Supplier<?> work){
        if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(com.dbcompanion.model.Ontology.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(IllegalArgumentException ex){return error(400,UiMessages.text("assistant.invalid","요청 정보를 확인해 주세요."));}
        catch(RuntimeException ex){return error(503,UiMessages.text("aitest.loadError","프로필 정보를 조회하지 못했습니다. 권한을 확인해 주세요.")+" · "+CredentialCatalogRepository.error(ex));}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    private PoolSession session(HttpServletRequest r){var http=r.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
}
