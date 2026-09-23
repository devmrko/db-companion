package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.SelectAiInspectionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-test/inspection")
public class SelectAiInspectionController {
    private final SelectAiInspectionService service;
    public SelectAiInspectionController(SelectAiInspectionService service){this.service=service;}
    public record Load(String profile,String question) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String name,Object value){throw new IllegalArgumentException("Unexpected request field");}
    }
    public record Table(String id,String owner,String name) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String name,Object value){throw new IllegalArgumentException("Unexpected request field");}
    }
    public record Feedback(String id,String search,int page) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String name,Object value){throw new IllegalArgumentException("Unexpected request field");}
    }
    public record Detail(String id,String rowId) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String name,Object value){throw new IllegalArgumentException("Unexpected request field");}
    }
    @PostMapping public ResponseEntity<?> load(@RequestBody Load v,HttpServletRequest r){return respond(r,s->service.load(s,v.profile(),v.question()));}
    @PostMapping("/table") public ResponseEntity<?> table(@RequestBody Table v,HttpServletRequest r){return respond(r,s->service.table(s,v.id(),v.owner(),v.name()));}
    @PostMapping("/feedback") public ResponseEntity<?> feedback(@RequestBody Feedback v,HttpServletRequest r){return respond(r,s->service.feedback(s,v.id(),v.search(),v.page()));}
    @PostMapping("/feedback/detail") public ResponseEntity<?> detail(@RequestBody Detail v,HttpServletRequest r){return respond(r,s->service.feedbackDetail(s,v.id(),v.rowId()));}
    private ResponseEntity<?> respond(HttpServletRequest r,Function<PoolSession,?> work){
        var http=r.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.apply(session));}
        catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(IllegalArgumentException ex){return error(400,UiMessages.text("assistant.invalid","요청 정보를 확인해 주세요."));}
        catch(RuntimeException ex){return error(503,UiMessages.text("aitest.inspectError","참고정보를 조회하지 못했습니다. 조회 권한과 프로필 상태를 확인해 주세요.")+" · "+CredentialCatalogRepository.error(ex));}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
}
