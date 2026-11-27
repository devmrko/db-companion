package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.AiExecutionHistory;
import com.dbcompanion.service.SelectAiResultArchiveService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SelectAiResultArchiveController {
    private final SelectAiResultArchiveService service;
    public SelectAiResultArchiveController(SelectAiResultArchiveService service){this.service=service;}
    @GetMapping("/ai-executions/results")
    public String page(HttpServletRequest request,HttpServletResponse response,Model model){
        var session=session(request);if(session==null)return "redirect:/login";
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("info",session.metadata().info());model.addAttribute("activePage","executions");
        return "select-ai-results";
    }
    public record Save(String resultId,boolean consent){
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unexpected request field");}
    }
    @PostMapping("/ai-test/results/save") @ResponseBody
    public ResponseEntity<?> save(@RequestBody Save value,HttpServletRequest request){return respond(request,()->Map.of("id",service.save(session(request),value.resultId(),value.consent())));}
    @GetMapping("/ai-executions/results/list") @ResponseBody
    public ResponseEntity<?> list(@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,
            @RequestParam(defaultValue="") String profile,@RequestParam(defaultValue="") String question,
            @RequestParam(defaultValue="1") String page,HttpServletRequest request){
        return respond(request,()->service.page(session(request),AiExecutionHistory.Query.parse(from,to,profile,question,page,LocalDate.now(AiExecutionHistory.ZONE))));
    }
    @GetMapping("/ai-executions/results/detail") @ResponseBody
    public ResponseEntity<?> detail(@RequestParam String id,HttpServletRequest request){return respond(request,()->service.detail(session(request),id));}
    private ResponseEntity<?> respond(HttpServletRequest request,Supplier<?> work){
        if(session(request)==null)return ResponseEntity.status(401).header("Cache-Control","no-store").body(Map.of("error","다시 로그인해 주세요."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(AiAssistant.Failure ex){return ResponseEntity.status(ex.status()).header("Cache-Control","no-store").body(Map.of("error",ex.getMessage()));}
        catch(AppException ex){return ResponseEntity.status(403).header("Cache-Control","no-store").body(Map.of("error",ex.userMessage()));}
        catch(IllegalArgumentException|java.time.DateTimeException ex){return ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of("error","기간(1~31일)·검색 조건·기록 ID를 확인해 주세요."));}
        catch(RuntimeException ex){return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error","보관한 조회 결과를 확인하지 못했습니다. 저장소와 연결 상태를 확인하세요. SQL을 다시 실행하지 않았습니다."));}
    }
    private PoolSession session(HttpServletRequest request){var http=request.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
}
