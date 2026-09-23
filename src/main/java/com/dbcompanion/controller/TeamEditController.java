package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.OracleErrorDetails;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.TeamEdit.*;
import com.dbcompanion.service.TeamEditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-agents/team")
public class TeamEditController {
    private final TeamEditService service;
    public TeamEditController(TeamEditService service){this.service=service;}
    @GetMapping("/attribute")
    public ResponseEntity<?> edit(@RequestParam String schema,@RequestParam String team,@RequestParam String attribute,HttpServletRequest request) {
        return invoke(request,UiMessages.text("ui.688085ae8014", "Team 편집 조회"),s -> service.edit(s,new Target(schema,team,attribute)));
    }
    @PostMapping("/attribute")
    public ResponseEntity<?> save(@RequestBody SaveRequest body,HttpServletRequest request) {
        return invoke(request,UiMessages.text("ui.acd6ce29807f", "저장 전 확인·원문 보관"),s -> service.save(s,body));
    }
    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestParam String schema,@RequestParam String team,@RequestParam(defaultValue="1") int page,HttpServletRequest request) {
        return invoke(request,UiMessages.text("ui.281fb46ffc56", "Team 이력 조회"),s -> service.history(s,new HistoryTarget(schema,team),page));
    }
    @GetMapping("/history/entry")
    public ResponseEntity<?> entry(@RequestParam String schema,@RequestParam String team,@RequestParam String seq,HttpServletRequest request) {
        return invoke(request,UiMessages.text("ui.3163f88e3e3f", "Team 이력 상세 조회"),s -> service.entry(s,new HistoryTarget(schema,team),seq));
    }
    @PostMapping("/history/install")
    public ResponseEntity<?> install(@RequestBody HistoryTarget body,HttpServletRequest request) {
        return invoke(request,UiMessages.text("ui.27a8da41ed7e", "Team 이력 설치 확인"),s -> service.install(s,body));
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
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Team preflight error: operation={}, code={}",operation,code);
            String detail=OracleErrorDetails.forDisplay(ex);
            return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",operation+UiMessages.text("ui.67b4c2ef81fb", " 중 오류가 발생했습니다. Team 속성 변경은 실행하지 않았습니다.\n")+detail));
        }
    }
}
