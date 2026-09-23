package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.*;
import com.dbcompanion.service.FeedbackTrackingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-feedback/tracking")
public class FeedbackTrackingController {
    private final FeedbackTrackingService service;
    public FeedbackTrackingController(FeedbackTrackingService service){this.service=service;}
    @GetMapping("/state") public ResponseEntity<?> state(@RequestParam String schema,@RequestParam String profile,HttpServletRequest request) {
        return invoke(request,s->service.state(s,schema,profile));
    }
    @PostMapping public ResponseEntity<?> change(@RequestBody FeedbackTrackingService.Request body,HttpServletRequest request) {
        return invoke(request,s->service.change(s,body));
    }
    @GetMapping("/history") public ResponseEntity<?> history(@RequestParam String schema,@RequestParam String profile,@RequestParam(defaultValue="1") int page,HttpServletRequest request) {
        return invoke(request,s->service.history(s,schema,profile,page));
    }
    @GetMapping("/entry") public ResponseEntity<?> entry(@RequestParam String schema,@RequestParam String profile,@RequestParam String seq,HttpServletRequest request) {
        return invoke(request,s->service.entry(s,schema,profile,seq));
    }
    private ResponseEntity<?> invoke(HttpServletRequest request,Function<PoolSession,Object> work) {
        var http=request.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return response(401,Map.of("error",UiMessages.text("ui.e0cca5f2b4e4", "로그인 세션이 만료되었습니다.")));
        try{return response(200,work.apply(session));}
        catch(MetadataEditException ex){return response(ex.status(),Map.of("error",ex.userMessage()));}
        catch(AppException ex){return response(403,Map.of("error",ex.userMessage()));}
        catch(IllegalArgumentException ex){return response(400,Map.of("error",UiMessages.text("ui.94be8f1a4a0a", "스키마·프로필·요청값을 확인해 주세요.")));}
        catch(RuntimeException ex){return response(503,Map.of("error",UiMessages.text("ui.40585569475a", "이력 상태 조회 오류 · ")+OracleErrorDetails.forDisplay(ex)));}
    }
    private ResponseEntity<?> response(int status,Object body){return ResponseEntity.status(status).header("Cache-Control","no-store").body(body);}
}
