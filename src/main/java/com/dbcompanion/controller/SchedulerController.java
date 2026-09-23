package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.Scheduler.Failure;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.SchedulerService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SchedulerController {
    private final SchedulerService service;
    public SchedulerController(SchedulerService service){this.service=service;}
    @GetMapping("/db/scheduler") public String page(HttpServletRequest request,Model model){
        var session=session(request);if(session==null)return "redirect:/login";
        synchronized(session){var data=session.metadata();model.addAttribute("info",data.info());model.addAttribute("schemas",data.schemas());model.addAttribute("selectedSchema",data.selectedSchema());model.addAttribute("activePage","scheduler");model.addAttribute("languageReturn","/db/scheduler");}
        return "scheduler";
    }
    @GetMapping("/db/scheduler/list") @ResponseBody public ResponseEntity<?> list(@RequestParam String schema,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest request){return read(request,()->service.list(session(request),schema,refresh));}
    @GetMapping("/db/scheduler/detail") @ResponseBody public ResponseEntity<?> detail(@RequestParam String schema,@RequestParam String name,HttpServletRequest request){return read(request,()->service.detail(session(request),schema,name));}
    @GetMapping("/db/scheduler/code") @ResponseBody public ResponseEntity<?> code(@RequestParam String schema,@RequestParam String name,HttpServletRequest request){return read(request,()->service.code(session(request),schema,name));}
    @GetMapping("/db/scheduler/runs") @ResponseBody public ResponseEntity<?> runs(@RequestParam String schema,@RequestParam String name,@RequestParam(defaultValue="") String before,HttpServletRequest request){return read(request,()->service.runs(session(request),schema,name,before));}
    @GetMapping("/db/scheduler/run") @ResponseBody public ResponseEntity<?> run(@RequestParam String schema,@RequestParam String name,@RequestParam String id,HttpServletRequest request){return read(request,()->service.run(session(request),schema,name,id));}
    private ResponseEntity<?> read(HttpServletRequest request,Supplier<?> work){
        if(session(request)==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(Failure ex){return error(ex.status(),ex.getMessage());}
        catch(IllegalArgumentException ex){return error(400,UiMessages.text("scheduler.invalid","조회 항목을 확인해 주세요."));}
        catch(RuntimeException ex){return error(503,UiMessages.text("scheduler.error","스케줄러 조회 오류")+" · "+CredentialCatalogRepository.error(ex));}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    private PoolSession session(HttpServletRequest request){var http=request.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
}
