package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.ExternalSources.*;
import com.dbcompanion.repository.ExternalSourcesRepository;
import com.dbcompanion.service.ExternalSourcesService;
import com.dbcompanion.service.MountedCatalogsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ExternalSourcesController {
    private final ExternalSourcesService service;
    private final MountedCatalogsService catalogs;
    public ExternalSourcesController(ExternalSourcesService service,MountedCatalogsService catalogs){this.service=service;this.catalogs=catalogs;}
    @GetMapping("/db/external-sources")
    public String page(HttpServletRequest request,Model model){
        var session=session(request);if(session==null)return "redirect:/login";
        synchronized(session){
            var data=session.metadata();model.addAttribute("info",data.info());model.addAttribute("schemas",data.schemas());
            model.addAttribute("selectedSchema",data.selectedSchema());model.addAttribute("activePage","external");model.addAttribute("languageReturn","/db/external-sources");
        }
        return "external-sources";
    }
    @GetMapping("/db/external-sources/list") @ResponseBody
    public ResponseEntity<?> list(@RequestParam String schema,@RequestParam String kind,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest request){
        return read(request,()->service.list(session(request),schema,Kind.valueOf(kind),refresh));
    }
    @GetMapping("/db/external-sources/detail") @ResponseBody
    public ResponseEntity<?> detail(@RequestParam String schema,@RequestParam String kind,@RequestParam String owner,@RequestParam String name,HttpServletRequest request){
        return read(request,()->service.detail(session(request),schema,Kind.valueOf(kind),owner,name));
    }
    @GetMapping("/db/external-sources/acl") @ResponseBody
    public ResponseEntity<?> acl(@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest request){
        return read(request,()->service.acl(session(request),refresh));
    }
    @GetMapping("/db/external-sources/acl/schema") @ResponseBody
    public ResponseEntity<?> schemaAcl(@RequestParam String schema,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest request){
        return read(request,()->service.schemaAcl(session(request),schema,refresh));
    }
    public static boolean safeReturn(String path){
        return path!=null&&path.matches("/db/external-sources(?:\\?tab=(links|tables|acl|catalogs)&aclView=(schema|all))?");
    }
    @GetMapping("/db/external-sources/catalogs") @ResponseBody
    public ResponseEntity<?> catalogs(@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest request){
        return read(request,()->catalogs.list(session(request),refresh));
    }
    @GetMapping("/db/external-sources/catalogs/detail") @ResponseBody
    public ResponseEntity<?> catalogDetail(@RequestParam String name,HttpServletRequest request){
        return read(request,()->catalogs.detail(session(request),name));
    }
    private ResponseEntity<?> read(HttpServletRequest request,Supplier<?> work){
        if(session(request)==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(Failure ex){return error(ex.status(),ex.getMessage());}
        catch(IllegalArgumentException ex){return error(400,UiMessages.text("external.invalid","조회 항목을 확인해 주세요."));}
        catch(RuntimeException ex){return error(503,UiMessages.text("external.error","외부 데이터 소스 조회 오류")+" · "+ExternalSourcesRepository.error(ex));}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    private PoolSession session(HttpServletRequest request){var http=request.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
}
