package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.SelectAiTest;
import com.dbcompanion.model.SelectAiTest.Action;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.SelectAiTestService;
import com.dbcompanion.service.ProblemQuestionService;
import com.dbcompanion.model.ProblemQuestion;
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
    private final ProblemQuestionService problems;
    public SelectAiTestController(SelectAiTestService service,ProblemQuestionService problems){this.service=service;this.problems=problems;}
    @GetMapping("/ai-test")
    public String page(HttpServletRequest request,Model model){
        var session=session(request);if(session==null)return "redirect:/login";
        model.addAttribute("info",session.metadata().info());model.addAttribute("activePage","ai-test");
        model.addAttribute("executionTimeoutSeconds",service.executionTimeoutSeconds());
        model.addAttribute("languageReturn","/ai-test");return "ai-test";
    }
    @GetMapping("/ai-test/options") @ResponseBody
    public ResponseEntity<?> options(@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest r){return respond(r,()->service.options(session(r),refresh));}
    @GetMapping("/ai-test/progress") @ResponseBody
    public ResponseEntity<?> progress(HttpServletRequest r){return respond(r,()->session(r).metadata().aiTest().progress().snapshots());}
    public record Choose(String name) {}
    @PostMapping("/ai-test/selection") @ResponseBody
    public ResponseEntity<?> selection(@RequestBody Choose v,HttpServletRequest r){return respond(r,()->{service.select(session(r),v.name());return Map.of("saved",true);});}
    @GetMapping("/ai-test/profile") @ResponseBody
    public ResponseEntity<?> profile(HttpServletRequest r){return respond(r,()->service.detail(session(r)));}
    @GetMapping("/ai-test/evidence/options") @ResponseBody
    public ResponseEntity<?> evidenceOptions(@RequestParam String schema,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest r){return respond(r,()->service.evidenceOptions(session(r),schema,refresh));}
    public record EvidenceSearch(String schema,String question,String anchor) {}
    @PostMapping("/ai-test/evidence/search") @ResponseBody
    public ResponseEntity<?> evidenceSearch(@RequestBody EvidenceSearch v,@RequestParam(defaultValue="") String language,Locale locale,HttpServletRequest r){return respond(r,()->service.evidenceSearch(session(r),v.schema(),v.question(),v.anchor(),QuestionAnalysisController.language(language,locale)));}
    public record EvidenceChoose(String searchId,String route) {}
    @PostMapping("/ai-test/evidence/choose") @ResponseBody
    public ResponseEntity<?> evidenceChoose(@RequestBody EvidenceChoose v,HttpServletRequest r){return respond(r,()->service.evidenceChoose(session(r),v.searchId(),v.route()));}
    public record EvidenceDefinitions(String schema,String question,List<String> tables) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected request field");}
    }
    @PostMapping("/ai-test/evidence/definitions") @ResponseBody
    public ResponseEntity<?> evidenceDefinitions(@RequestBody EvidenceDefinitions v,HttpServletRequest r){return respond(r,()->service.evidenceDefinitions(session(r),v.schema(),v.question(),v.tables()));}
    public record Prepare(Action action,String question,boolean useOntology,String evidenceHash,com.dbcompanion.model.BusinessGlossary.Selection glossary) {
        public Prepare(Action action,String question,boolean useOntology,String evidenceHash){this(action,question,useOntology,evidenceHash,null);}
    }
    @PostMapping("/ai-test/preview") @ResponseBody
    public ResponseEntity<?> preview(@RequestBody Prepare v,Locale locale,HttpServletRequest r){return respond(r,()->service.preview(session(r),v.action(),v.question(),v.useOntology(),v.evidenceHash(),locale,v.glossary()));}
    public record ConditionPreview(Action action,String question,String originalQuestion,String confirmationQuestion,String confirmationAnswer,List<SelectAiTest.ConfirmedCondition> conditions,boolean useOntology,String evidenceHash,com.dbcompanion.model.BusinessGlossary.Selection glossary) {
        public ConditionPreview(Action action,String question,String originalQuestion,String confirmationQuestion,String confirmationAnswer,List<SelectAiTest.ConfirmedCondition> conditions,boolean useOntology,String evidenceHash){this(action,question,originalQuestion,confirmationQuestion,confirmationAnswer,conditions,useOntology,evidenceHash,null);}
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected request field");}
    }
    @PostMapping("/ai-test/condition/preview") @ResponseBody
    public ResponseEntity<?> conditionPreview(@RequestBody ConditionPreview v,Locale locale,HttpServletRequest r){return respond(r,()->{if(!Objects.equals(v.question(),v.originalQuestion()))throw AiAssistant.stale();return service.conditionPreview(session(r),v.action(),v.question(),new SelectAiTest.ConditionConfirmation(v.originalQuestion(),v.confirmationQuestion(),v.confirmationAnswer(),v.conditions()),v.useOntology(),v.evidenceHash(),locale,v.glossary());});}
    public record Run(String token,boolean consent) {}
    public record ReviewPrepare(String promptId) {}
    @PostMapping("/ai-test/review/preview") @ResponseBody
    public ResponseEntity<?> reviewPreview(@RequestBody ReviewPrepare v,Locale locale,HttpServletRequest r){return respond(r,()->service.reviewPreview(session(r),v.promptId(),locale));}
    @PostMapping("/ai-test/review") @ResponseBody
    public ResponseEntity<?> review(@RequestBody Run v,HttpServletRequest r){return respond(r,()->service.review(session(r),v.token(),v.consent()));}
    @PostMapping("/ai-test/generate") @ResponseBody
    public ResponseEntity<?> generate(@RequestBody Run v,HttpServletRequest r){return respond(r,()->service.run(session(r),v.token(),v.consent(),r.getHeader("X-AI-Progress-Id")));}
    public record ComparisonPrepare(String left,String right,String question) {}
    @PostMapping("/ai-test/comparison/preview") @ResponseBody public ResponseEntity<?> comparisonPreview(@RequestBody ComparisonPrepare v,Locale locale,HttpServletRequest r){return respond(r,()->service.comparisonPreview(session(r),v.left(),v.right(),v.question(),locale));}
    @PostMapping("/ai-test/comparison/generate") @ResponseBody public ResponseEntity<?> comparisonGenerate(@RequestBody Run v,HttpServletRequest r){return respond(r,()->service.comparisonGenerate(session(r),v.token(),v.consent()));}
    @PostMapping("/ai-test/comparison/showprompt/preview") @ResponseBody public ResponseEntity<?> comparisonShowPromptPreview(Locale locale,HttpServletRequest r){return respond(r,()->service.comparisonShowPromptPreview(session(r),locale));}
    @PostMapping("/ai-test/comparison/showprompt") @ResponseBody public ResponseEntity<?> comparisonShowPrompt(@RequestBody Run v,HttpServletRequest r){return respond(r,()->service.comparisonShowPrompt(session(r),v.token(),v.consent()));}
    @GetMapping("/ai-test/comparison/result") @ResponseBody public ResponseEntity<?> comparisonResult(HttpServletRequest r){return respond(r,()->service.comparisonResult(session(r)));}
    public record ComparisonInspection(String side) {}
    @PostMapping("/ai-test/comparison/inspection") @ResponseBody public ResponseEntity<?> comparisonInspection(@RequestBody ComparisonInspection v,HttpServletRequest r){return respond(r,()->service.comparisonInspection(session(r),v.side()));}
    public record ComparisonTable(String side,String id,String owner,String name) {}
    @PostMapping("/ai-test/comparison/inspection/table") @ResponseBody public ResponseEntity<?> comparisonTable(@RequestBody ComparisonTable v,HttpServletRequest r){return respond(r,()->service.comparisonTable(session(r),v.side(),v.id(),v.owner(),v.name()));}
    public record ComparisonFeedbackDetail(String side,String id,String rowId) {}
    @PostMapping("/ai-test/comparison/inspection/feedback/detail") @ResponseBody public ResponseEntity<?> comparisonFeedbackDetail(@RequestBody ComparisonFeedbackDetail v,HttpServletRequest r){return respond(r,()->service.comparisonFeedbackDetail(session(r),v.side(),v.id(),v.rowId()));}
    public record ComparisonAiPrepare(Set<String> fields) {}
    @PostMapping("/ai-test/comparison/ai-preview") @ResponseBody public ResponseEntity<?> comparisonAiPreview(@RequestBody ComparisonAiPrepare v,HttpServletRequest r){return respond(r,()->service.comparisonAiPreview(session(r),v.fields()));}
    @PostMapping("/ai-test/comparison/ai") @ResponseBody public ResponseEntity<?> comparisonAi(@RequestBody Run v,HttpServletRequest r){return respond(r,()->Map.of("text",service.comparisonAi(session(r),v.token(),v.consent())));}
    public record SavePreview(String resultId) {@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected request field");}}
    public record ComparisonSavePreview(String generation,String side,String resultId) {@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected request field");}}
    public record SaveProblem(String resultId,String saveToken,String description,String expected,String expectedSql,ProblemQuestion.Status status,boolean includeSnapshots) {@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected request field");}}
    @PostMapping("/ai-test/problem/save-preview") @ResponseBody
    public ResponseEntity<?> problemSavePreview(@RequestBody SavePreview v,HttpServletRequest r){return respond(r,()->service.problemSavePreview(session(r),v.resultId()));}
    @PostMapping("/ai-test/problem") @ResponseBody
    public ResponseEntity<?> saveProblem(@RequestBody SaveProblem v,HttpServletRequest r){return respond(r,()->{var create=ProblemQuestion.create(new ProblemQuestion.Create("pending",v.description(),v.expected(),v.expectedSql(),v.status()));var begin=service.problemSave(session(r),v.resultId(),v.saveToken(),purpose(null,create,v.includeSnapshots()));if(begin.savedId()!=null)return Map.of("id",begin.savedId());try{var capture=problems.capture(begin.selection().outcome(),begin.selection().prompt(),begin.selection().inspection(),v.includeSnapshots());String id=problems.saveCaptured(session(r),new ProblemQuestion.Create(capture.outcome().question(),create.description(),create.expected(),create.expectedSql(),create.status()),capture);service.problemSaveFinished(session(r),v.saveToken(),id);return Map.of("id",id);}catch(RuntimeException ex){resolveSaveFailure(ex,()->service.problemSaveAborted(session(r),v.saveToken()),()->service.problemSaveUnconfirmed(session(r),v.saveToken()));return null;}});}
    public record SaveProblemAttempt(String resultId,String saveToken,String parentId,String parentUpdatedAt,boolean includeSnapshots) {@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected request field");}}
    @PostMapping("/ai-test/problem/attempt") @ResponseBody
    public ResponseEntity<?> saveProblemAttempt(@RequestBody SaveProblemAttempt v,HttpServletRequest r){return respond(r,()->{var parent=checkedParent(session(r),v.parentId(),v.parentUpdatedAt());var begin=service.problemSave(session(r),v.resultId(),v.saveToken(),purpose(parent,null,v.includeSnapshots()));if(begin.savedId()!=null)return Map.of("id",begin.savedId());try{var capture=problems.capture(begin.selection().outcome(),begin.selection().prompt(),begin.selection().inspection(),v.includeSnapshots());String id=problems.saveCapturedAttempt(session(r),parent.id(),parent.updatedAt(),capture);service.problemSaveFinished(session(r),v.saveToken(),id);return Map.of("id",id);}catch(RuntimeException ex){resolveSaveFailure(ex,()->service.problemSaveAborted(session(r),v.saveToken()),()->service.problemSaveUnconfirmed(session(r),v.saveToken()));return null;}});}
    @PostMapping("/ai-test/comparison/problem/save-preview") @ResponseBody public ResponseEntity<?> comparisonProblemSavePreview(@RequestBody ComparisonSavePreview v,HttpServletRequest r){return respond(r,()->service.comparisonSavePreview(session(r),v.generation(),v.side(),v.resultId()));}
    public record ComparisonSave(String generation,String side,String resultId,String saveToken,String parentId,String parentUpdatedAt,String description,String expected,String expectedSql,ProblemQuestion.Status status,boolean includeSnapshots) {@com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected request field");}}
    @PostMapping("/ai-test/comparison/problem") @ResponseBody public ResponseEntity<?> comparisonProblem(@RequestBody ComparisonSave v,HttpServletRequest r){return respond(r,()->{var create=ProblemQuestion.create(new ProblemQuestion.Create("pending",v.description(),v.expected(),v.expectedSql(),v.status()));var begin=service.comparisonSave(session(r),v.generation(),v.side(),v.resultId(),v.saveToken(),purpose(null,create,v.includeSnapshots()));if(begin.savedId()!=null)return Map.of("id",begin.savedId());try{var capture=problems.capture(begin.selection().outcome(),begin.selection().prompt(),begin.selection().inspection(),v.includeSnapshots());String id=problems.saveCaptured(session(r),new ProblemQuestion.Create(capture.outcome().question(),create.description(),create.expected(),create.expectedSql(),create.status()),capture);service.comparisonSaveFinished(session(r),v.saveToken(),id);return Map.of("id",id);}catch(RuntimeException ex){resolveSaveFailure(ex,()->service.comparisonSaveAborted(session(r),v.saveToken()),()->service.comparisonSaveUnconfirmed(session(r),v.saveToken()));return null;}});}
    @PostMapping("/ai-test/comparison/problem/attempt") @ResponseBody public ResponseEntity<?> comparisonProblemAttempt(@RequestBody ComparisonSave v,HttpServletRequest r){return respond(r,()->{var parent=checkedParent(session(r),v.parentId(),v.parentUpdatedAt());var begin=service.comparisonSave(session(r),v.generation(),v.side(),v.resultId(),v.saveToken(),purpose(parent,null,v.includeSnapshots()));if(begin.savedId()!=null)return Map.of("id",begin.savedId());try{var capture=problems.capture(begin.selection().outcome(),begin.selection().prompt(),begin.selection().inspection(),v.includeSnapshots());String id=problems.saveCapturedAttempt(session(r),parent.id(),parent.updatedAt(),capture);service.comparisonSaveFinished(session(r),v.saveToken(),id);return Map.of("id",id);}catch(RuntimeException ex){resolveSaveFailure(ex,()->service.comparisonSaveAborted(session(r),v.saveToken()),()->service.comparisonSaveUnconfirmed(session(r),v.saveToken()));return null;}});}
    public record Cancel(String token) {}
    public record ExecutionPrepare(String resultId) {}
    @PostMapping("/ai-test/execute/preview") @ResponseBody
    public ResponseEntity<?> executionPreview(@RequestBody ExecutionPrepare v,HttpServletRequest r){return respond(r,()->service.executionPreview(session(r),v.resultId()));}
    @PostMapping("/ai-test/execute") @ResponseBody
    public ResponseEntity<?> execute(@RequestBody Run v,HttpServletRequest r){return respond(r,()->service.execute(session(r),v.token(),v.consent(),r.getHeader("X-AI-Progress-Id")));}
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
    /** Known validation/readiness failures prove no write completed; only unknown I/O becomes unconfirmed. */
    static void resolveSaveFailure(RuntimeException ex,Runnable abort,Runnable unconfirmed){if(ex instanceof AiAssistant.Failure||ex instanceof com.dbcompanion.model.Ontology.Failure){abort.run();throw ex;}unconfirmed.run();throw new AiAssistant.Failure(503,"problemQuestion.saveUnconfirmed","저장 결과를 확인하지 못했습니다. 자동 재전송하지 않습니다.");}
    private ProblemQuestion.Parent checkedParent(PoolSession session,String id,String seenUpdatedAt){var parent=problems.detail(session,id).parent();if(seenUpdatedAt==null||!seenUpdatedAt.equals(parent.updatedAt().toString()))throw AiAssistant.stale();return parent;}
    private String purpose(ProblemQuestion.Parent parent,ProblemQuestion.Create create,boolean snapshots){return parent!=null?"parent\u0000"+parent.id()+"\u0000"+parent.updatedAt()+"\u0000"+snapshots:"new\u0000"+create.description()+"\u0000"+create.expected()+"\u0000"+create.expectedSql()+"\u0000"+create.status()+"\u0000"+snapshots;}
    private PoolSession session(HttpServletRequest r){var http=r.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
}
