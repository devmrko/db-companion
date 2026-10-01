package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.SqlArchiveAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-executions/sql/archive/access")
public class SqlArchiveAccessController {
    private static final String PREVIEW=SqlArchiveAccessController.class.getName()+".preview";
    private final SqlArchiveAccessService service;
    public SqlArchiveAccessController(SqlArchiveAccessService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    @GetMapping("/users") public ResponseEntity<?> users(HttpServletRequest r){return respond(r,()->service.users(session(r)));}
    @GetMapping public ResponseEntity<?> inspect(@RequestParam String username,HttpServletRequest r){return respond(r,()->service.inspect(session(r),username));}
    public record Prepare(String username){}
    @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody Prepare value,HttpServletRequest r){return respond(r,()->{
        synchronized(session(r)){r.getSession().removeAttribute(PREVIEW);var p=service.preview(session(r),value.username());r.getSession().setAttribute(PREVIEW,p);return p;}
    });}
    public record Apply(String token,boolean consent){}
    @PostMapping("/apply") public ResponseEntity<?> apply(@RequestBody Apply value,HttpServletRequest r){return respond(r,()->{
        synchronized(session(r)){
            var p=(SqlArchiveAccessService.Preview)r.getSession().getAttribute(PREVIEW);
            if(!value.consent()||p==null||!p.token().equals(value.token()))throw new IllegalArgumentException("Confirmation required");
            r.getSession().removeAttribute(PREVIEW);return service.apply(session(r),p);
        }
    });}
    private ResponseEntity<?> respond(HttpServletRequest r,Supplier<?> work){
        if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다."));
        if(!"ADMIN".equals(session(r).metadata().info().username()))return error(403,UiMessages.text("archive.access.adminOnly","ADMIN 로그인에서만 권한을 부여할 수 있습니다."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(SecurityException ex){return error(403,UiMessages.text("archive.access.adminOnly","ADMIN 로그인에서만 권한을 부여할 수 있습니다."));}
        catch(IllegalArgumentException ex){return error(400,UiMessages.text("archive.invalid","조건·권한·설정 상태를 확인하고 SQL 미리보기를 다시 열어 주세요."));}
        catch(RuntimeException ex){return error(503,UiMessages.text("archive.access.failed","권한 적용 여부를 확인하지 못했습니다. 일부 권한은 부여됐을 수 있으므로 다시 조회하세요.")+" · "+CredentialCatalogRepository.error(ex));}
    }
    private ResponseEntity<?> error(int status,String text){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",text));}
}
