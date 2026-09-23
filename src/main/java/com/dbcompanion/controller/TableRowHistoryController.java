package com.dbcompanion.controller;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.TableRowHistory.Request;
import com.dbcompanion.model.VectorSearch;
import com.dbcompanion.service.TableRowHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/vector-search/history")
public class TableRowHistoryController {
    private final TableRowHistoryService service;
    public TableRowHistoryController(TableRowHistoryService service){this.service=service;}
    @GetMapping("/state") public ResponseEntity<?> state(@RequestParam String schema,@RequestParam String table,
            @RequestParam(defaultValue="false") boolean refresh,HttpServletRequest request){return invoke(request,s->service.state(s,schema,table,refresh));}
    @PostMapping public ResponseEntity<?> change(@RequestBody Request body,HttpServletRequest request){return invoke(request,s->service.change(s,body));}
    @GetMapping public ResponseEntity<?> history(@RequestParam String schema,@RequestParam String table,
            @RequestParam(defaultValue="1") int page,HttpServletRequest request){return invoke(request,s->service.history(s,schema,table,page));}
    @GetMapping("/entry") public ResponseEntity<?> entry(@RequestParam String schema,@RequestParam String table,@RequestParam String seq,HttpServletRequest request){return invoke(request,s->service.entry(s,schema,table,seq));}
    private ResponseEntity<?> invoke(HttpServletRequest request,Function<PoolSession,Object> work) {
        var http=request.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return response(401,Map.of("error",UiMessages.text("ui.e0cca5f2b4e4","로그인 세션이 만료되었습니다.")));
        try{return response(200,work.apply(session));}
        catch(MetadataEditException ex){return response(ex.status(),Map.of("error",ex.userMessage()));}
        catch(VectorSearch.Failure ex){return response(ex.status(),Map.of("error",ex.getMessage()));}
        catch(IllegalArgumentException ex){return response(400,Map.of("error",UiMessages.text("rowHistory.invalid","스키마·테이블·요청값을 확인해 주세요.")));}
        catch(RuntimeException ex){return response(503,Map.of("error",UiMessages.text("ui.40585569475a","이력 상태 조회 오류 · ")+OracleErrorDetails.forDisplay(ex)));}
    }
    private ResponseEntity<?> response(int code,Object body){return ResponseEntity.status(code).header("Cache-Control","no-store").body(body);}
}
