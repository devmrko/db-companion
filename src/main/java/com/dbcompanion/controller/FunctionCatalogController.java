package com.dbcompanion.controller;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.FunctionCatalog.Failure;
import com.dbcompanion.service.FunctionCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class FunctionCatalogController {
    private final FunctionCatalogService service;
    public FunctionCatalogController(FunctionCatalogService service){this.service=service;}
    @GetMapping("/db/functions")
    public String page(@RequestParam(defaultValue="") String name,@RequestParam(required=false) String schema,HttpServletRequest request,Model model){
        var session=session(request);if(session==null)return "redirect:/login";
        synchronized(session){
            var metadata=session.metadata();model.addAttribute("info",metadata.info());model.addAttribute("schemas",metadata.schemas());
            model.addAttribute("selectedSchema",metadata.selectedSchema());model.addAttribute("activePage","functions");
            model.addAttribute("routineName",name);
            if(schema!=null&&!schema.equals(metadata.selectedSchema()))model.addAttribute("loadError",UiMessages.text("ui.b05af1b875ea","스키마가 변경되었습니다. 화면을 새로고침해 주세요."));
        }
        return "functions";
    }
    @GetMapping("/db/functions/list") @ResponseBody
    public ResponseEntity<?> list(@RequestParam String schema,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest request){
        return read(request,()->service.list(session(request),schema,refresh));
    }
    @GetMapping("/db/functions/detail") @ResponseBody
    public ResponseEntity<?> detail(@RequestParam String schema,@RequestParam String name,HttpServletRequest request){
        return read(request,()->service.detail(session(request),schema,name));
    }
    private ResponseEntity<?> read(HttpServletRequest request,Supplier<?> work){
        if(session(request)==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(Failure ex){return error(ex.status(),ex.getMessage());}
        catch(IllegalArgumentException ex){return error(400,UiMessages.text("ui.19aab2f2cd6c","등록된 이름의 형식을 확인해 주세요. 로컬 객체 이름만 조회할 수 있습니다."));}
        catch(RuntimeException ex){
            String details=OracleErrorDetails.forDisplay(ex);
            return error(503,UiMessages.text("functions.loadError","함수 정보를 조회하지 못했습니다. 조회 권한을 확인해 주세요.")+(details.isEmpty()?"":" · "+details));
        }
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    private PoolSession session(HttpServletRequest request){var http=request.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
}
