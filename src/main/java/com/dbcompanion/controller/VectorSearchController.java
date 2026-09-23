package com.dbcompanion.controller;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.VectorSearch.*;
import com.dbcompanion.service.VectorSearchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class VectorSearchController {
    private final VectorSearchService service;
    public VectorSearchController(VectorSearchService service) { this.service=service; }
    private PoolSession session(HttpServletRequest request) {
        var http=request.getSession(false); return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
    }
    @GetMapping("/vector-search")
    public String page(@RequestParam(defaultValue="") String schema,@RequestParam(defaultValue="") String table,
            @RequestParam(defaultValue="false") boolean refresh,HttpServletRequest request,HttpServletResponse response,Model model) {
        var session=session(request);if(session==null)return "redirect:/login";
        response.setHeader("Cache-Control","no-store");
        synchronized(session) {
            var state=session.metadata();model.addAttribute("activePage","vectors");model.addAttribute("info",state.info());
            model.addAttribute("schemas",state.schemas());model.addAttribute("selectedSchema",state.selectedSchema());model.addAttribute("tableName",table);
            try {
                if(!schema.isEmpty()) com.dbcompanion.model.VectorSearch.scope(state.selectedSchema(),schema);
                if(table.isEmpty()) model.addAttribute("vectorTables",service.tables(session,refresh));
                else { com.dbcompanion.model.VectorSearch.name(table); }
            } catch(Failure ex) { response.setStatus(ex.status());model.addAttribute("loadError",ex.getMessage()); }
            catch(RuntimeException ex) { response.setStatus(503);model.addAttribute("loadError",failure(UiMessages.text("ui.8fe7a9578dd0", "벡터 테이블 목록"),ex)); }
        }
        return "vector-search";
    }
    @GetMapping("/vector-search/metadata") @ResponseBody
    public ResponseEntity<?> metadata(@RequestParam String schema,@RequestParam String table,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest request) {
        return read(request,UiMessages.text("ui.528ca6a2f2f8", "벡터 컬럼·임베딩 옵션"),()->service.metadata(session(request),schema,table,refresh));
    }
    @GetMapping("/vector-search/rows") @ResponseBody
    public ResponseEntity<?> rows(@RequestParam String schema,@RequestParam String table,@RequestParam String vector,
            @RequestParam(defaultValue="") String content,@RequestParam(defaultValue="1") int page,HttpServletRequest request) {
        return read(request,UiMessages.text("ui.e921be8e69cd", "데이터"),()->service.rows(session(request),new Selection(schema,table,vector,content),page));
    }
    @GetMapping("/vector-search/detail") @ResponseBody
    public ResponseEntity<?> detail(@RequestParam String schema,@RequestParam String table,@RequestParam String vector,
            @RequestParam(defaultValue="") String content,@RequestParam String id,HttpServletRequest request) {
        return read(request,UiMessages.text("ui.e9548500167d", "행 상세"),()->service.detail(session(request),new Selection(schema,table,vector,content),id));
    }
    @PostMapping("/vector-search/search") @ResponseBody
    public ResponseEntity<?> search(@RequestBody Search search,HttpServletRequest request) {
        return read(request,UiMessages.text("ui.fee12485ed4c", "벡터 검색"),()->service.search(session(request),search));
    }
    @PostMapping("/vector-search/oci-models") @ResponseBody
    public ResponseEntity<?> ociModels(@RequestBody com.dbcompanion.model.OciEmbeddingModels.Request query,HttpServletRequest request) {
        return read(request,UiMessages.text("vector.oci.catalog","OCI 임베딩 모델"),()->service.ociModels(session(request),query));
    }
    private ResponseEntity<?> read(HttpServletRequest request,String stage,Supplier<?> work) {
        if(session(request)==null)return ResponseEntity.status(401).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("ui.e0cca5f2b4e4", "로그인 세션이 만료되었습니다.")));
        try { return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get()); }
        catch(Failure ex) { return ResponseEntity.status(ex.status()).header("Cache-Control","no-store").body(Map.of("error",ex.getMessage())); }
        catch(RuntimeException ex) { return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",failure(stage,ex))); }
    }
    private String failure(String stage,RuntimeException ex) {
        Throwable cause=ex;while(cause.getCause()!=null && !(cause instanceof SQLException))cause=cause.getCause();
        int code=cause instanceof SQLException sql?sql.getErrorCode():0;
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Vector explorer error: stage={} code={}",stage,code);
        return stage+UiMessages.text("ui.55a7ce8a17aa", " 조회 오류 · Oracle code=")+code+" · "+OracleErrorDetails.forDisplay(ex)
                +(stage.equals(UiMessages.text("ui.fee12485ed4c", "벡터 검색"))?UiMessages.text("ui.b3c588ec6b18", " · 자동 재시도하지 않았습니다. 외부 임베딩 호출이 시작됐다면 사용량이 발생했을 수 있습니다."):"");
    }
}
