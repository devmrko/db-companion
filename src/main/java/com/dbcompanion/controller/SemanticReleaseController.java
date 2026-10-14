package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.*;
import com.dbcompanion.service.SemanticReleaseService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/semantic-releases")
public class SemanticReleaseController {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SemanticReleaseController.class);
    private final SemanticReleaseService service;
    public SemanticReleaseController(SemanticReleaseService service){this.service=service;}
    public record Create(String name,boolean confirmed){}
    public record Select(String selection,SemanticRelease.Preference expected,boolean confirmed){}
    public record Deploy(String id,SemanticRelease.Head expected,String reason,boolean confirmed){}
    public record Initialize(boolean confirmed){}
    public record StorageInstall(String token,boolean confirmed){}
    public record Revise(String parentId,String name,List<SemanticRelease.TermRef> terms,List<SemanticRelease.SourceRef> sources,boolean confirmed){}
    @GetMapping public ResponseEntity<?> status(HttpServletRequest r){return run(r,()->service.status(session(r)));}
    @GetMapping("/catalog") public ResponseEntity<?> catalog(HttpServletRequest r){return run(r,()->service.catalog(session(r)));}
    @GetMapping("/{id}") public ResponseEntity<?> detail(@PathVariable String id,HttpServletRequest r){return run(r,()->service.detail(session(r),id));}
    @PostMapping("/create") public ResponseEntity<?> create(@RequestBody Create v,HttpServletRequest r){return run(r,()->service.create(session(r),v.name(),v.confirmed()));}
    @PostMapping("/initialize") public ResponseEntity<?> initialize(@RequestBody Initialize v,HttpServletRequest r){return run(r,()->service.initialize(session(r),v.confirmed()));}
    @PostMapping("/storage-preview") public ResponseEntity<?> storagePreview(HttpServletRequest r){return run(r,()->service.storagePreview(session(r)));}
    @PostMapping("/storage-install") public ResponseEntity<?> storageInstall(@RequestBody StorageInstall v,HttpServletRequest r){return run(r,()->service.storageInstall(session(r),v.token(),v.confirmed()));}
    @PostMapping("/revise") public ResponseEntity<?> revise(@RequestBody Revise v,HttpServletRequest r){return run(r,()->service.revise(session(r),v.parentId(),v.name(),v.terms(),v.sources(),v.confirmed()));}
    @PostMapping("/select") public ResponseEntity<?> select(@RequestBody Select v,HttpServletRequest r){return run(r,()->service.select(session(r),v.selection(),v.expected(),v.confirmed()));}
    @PostMapping("/deploy") public ResponseEntity<?> deploy(@RequestBody Deploy v,HttpServletRequest r){return run(r,()->service.deploy(session(r),v.id(),v.expected(),v.reason(),v.confirmed()));}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> work){
        if(session(r)==null)return error(401,"Login required");
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(AiAssistant.Failure e){return error(e.status(),e.getMessage());}
        catch(Ontology.Failure e){return error(e.status(),e.getMessage());}
        catch(RuntimeException e){
            int code=com.dbcompanion.common.db.OracleErrorDetails.firstCode(e);
            log.warn("Semantic operation failed path={} type={} oracleCode={}",r.getServletPath(),e.getClass().getSimpleName(),code);
            boolean timeout=code==1013||e instanceof org.springframework.transaction.TransactionTimedOutException;
            return error(timeout?504:503,(timeout?"배포 버전 처리 시간이 초과되었습니다.":"배포 버전 처리에 실패했습니다.")+(code==0?"":" (ORA-"+String.format(java.util.Locale.ROOT,"%05d",code)+")")+" 새로고침하여 저장 여부를 먼저 확인해 주세요. 자동 재시도하지 않습니다.");
        }
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<?> invalid(){return error(400,"Invalid semantic version request");}
}
