package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.OracleErrorDetails;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AgentCatalog.Kind;
import com.dbcompanion.model.AgentObjectEdit.*;
import com.dbcompanion.model.TeamEdit.SaveResult;
import com.dbcompanion.service.AgentObjectEditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-agents/object")
public class AgentObjectEditController {
    private final AgentObjectEditService service;
    public AgentObjectEditController(AgentObjectEditService service){this.service=service;}
    @GetMapping("/attribute")
    public ResponseEntity<?> edit(@RequestParam String schema,@RequestParam Kind kind,@RequestParam String name,@RequestParam String attribute,HttpServletRequest request) {
        return invoke(request,UiMessages.text("ui.414997337aff", "속성 조회"),s->service.edit(s,new Target(schema,kind,name,attribute)));
    }
    @PostMapping("/attribute")
    public ResponseEntity<?> save(@RequestBody SaveRequest body,HttpServletRequest request) {
        return invoke(request,UiMessages.text("ui.acd6ce29807f", "저장 전 확인·원문 보관"),s->service.save(s,body));
    }
    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestParam String schema,@RequestParam Kind kind,@RequestParam String name,@RequestParam(defaultValue="1") int page,HttpServletRequest request) {
        return invoke(request,UiMessages.text("ui.c80171389554", "이력 조회"),s->service.history(s,new HistoryTarget(schema,kind,name),page));
    }
    @GetMapping("/history/entry")
    public ResponseEntity<?> entry(@RequestParam String schema,@RequestParam Kind kind,@RequestParam String name,@RequestParam String seq,HttpServletRequest request) {
        return invoke(request,UiMessages.text("ui.0feeded50606", "이력 상세 조회"),s->service.entry(s,new HistoryTarget(schema,kind,name),seq));
    }
    @PostMapping("/history/install")
    public ResponseEntity<?> install(@RequestBody HistoryTarget body,HttpServletRequest request) {
        return invoke(request,UiMessages.text("ui.313d02b91246", "이력 설치 확인"),s->service.install(s,body));
    }
    private ResponseEntity<?> invoke(HttpServletRequest request,String operation,Function<PoolSession,Object> action) {
        var http=request.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return ResponseEntity.status(401).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("ui.e0cca5f2b4e4", "로그인 세션이 만료되었습니다.")));
        try {
            Object result=action.apply(session);
            return ResponseEntity.status(result instanceof SaveResult saved&&!saved.verified()?202:200).header("Cache-Control","no-store").body(result);
        }catch(MetadataEditException ex){return ResponseEntity.status(ex.status()).header("Cache-Control","no-store").body(Map.of("error",ex.userMessage()));}
        catch(RuntimeException ex) {
            int code=0;for(Throwable cause=ex;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql){code=sql.getErrorCode();break;}
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("AI object preflight error: operation={}, code={}",operation,code);
            return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",operation+UiMessages.text("ui.84130fa01d8d", " 중 오류가 발생했습니다. 객체 속성 변경은 실행하지 않았습니다.\n")+OracleErrorDetails.forDisplay(ex)));
        }
    }
}
