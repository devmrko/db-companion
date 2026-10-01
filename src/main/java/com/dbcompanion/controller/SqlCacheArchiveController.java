package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.SqlCacheArchive.*;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.repository.SqlCacheArchiveRepository;
import com.dbcompanion.service.SqlCacheArchiveService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/ai-executions/sql/archive")
public class SqlCacheArchiveController {
    private static final String PREVIEW=SqlCacheArchiveController.class.getName()+".preview";
    private final SqlCacheArchiveService service;
    public SqlCacheArchiveController(SqlCacheArchiveService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    @GetMapping public String page(HttpServletRequest r,HttpServletResponse response,Model m){
        var s=session(r);if(s==null)return "redirect:/login";response.setHeader("Cache-Control","no-store");
        m.addAttribute("info",s.metadata().info());m.addAttribute("activePage","executions");m.addAttribute("languageReturn","/ai-executions/sql/archive");
        m.addAttribute("archiveAdmin","ADMIN".equals(s.metadata().info().username()));
        m.addAttribute("today",LocalDate.now(ZoneOffset.UTC));m.addAttribute("from",LocalDate.now(ZoneOffset.UTC).minusDays(6));return "sql-cache-archive";
    }
    @GetMapping("/status") @ResponseBody public ResponseEntity<?> status(HttpServletRequest r){return respond(r,()->service.status(session(r)));}
    @GetMapping("/collector") @ResponseBody public ResponseEntity<?> collector(HttpServletRequest r){return respond(r,()->Map.of("sql",SqlCacheArchiveRepository.helpScript()));}
    @GetMapping("/list") @ResponseBody public ResponseEntity<?> list(@RequestParam String from,@RequestParam String to,
        @RequestParam(defaultValue="")String sqlId,@RequestParam(defaultValue="")String text,@RequestParam(defaultValue="all")String match,
        @RequestParam(defaultValue="1")int page,HttpServletRequest r){
        return respond(r,()->service.page(session(r),new Query(LocalDate.parse(from),LocalDate.parse(to),sqlId,text,Match.valueOf(match),page)));
    }
    @GetMapping("/detail") @ResponseBody public ResponseEntity<?> detail(@RequestParam String key,HttpServletRequest r){return respond(r,()->service.detail(session(r),key));}
    public record Prepare(Operation operation,int seconds){}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody Prepare v,HttpServletRequest r){
        return respond(r,()->{synchronized(session(r)){var p=service.preview(session(r),v.operation(),v.seconds());r.getSession().setAttribute(PREVIEW,p);return p;}});
    }
    public record Apply(String token,boolean consent){}
    @PostMapping("/apply") @ResponseBody public ResponseEntity<?> apply(@RequestBody Apply v,HttpServletRequest r){
        return respond(r,()->{synchronized(session(r)){
            var p=(Preview)r.getSession().getAttribute(PREVIEW);
            if(!v.consent() || p==null || !p.token().equals(v.token()))throw new IllegalArgumentException("Confirmation required");
            r.getSession().removeAttribute(PREVIEW);
            return service.apply(session(r),p);
        }});
    }
    private ResponseEntity<?> respond(HttpServletRequest r,Supplier<?> work){
        if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(IllegalArgumentException | java.time.DateTimeException ex){return error(400,UiMessages.text("archive.invalid","조건·권한·설정 상태를 확인하고 SQL 미리보기를 다시 열어 주세요."));}
        catch(RuntimeException ex){return error(503,UiMessages.text("archive.failed","요청을 확인하지 못했습니다. DDL은 일부 반영될 수 있습니다. 자동 재시도하지 말고 상태를 새로고침하세요.")+" · "+CredentialCatalogRepository.error(ex));}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
}
