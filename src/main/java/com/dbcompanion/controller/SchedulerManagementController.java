package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.SchedulerManagement.*;
import com.dbcompanion.model.OrdsManagement;
import com.dbcompanion.repository.OrdsManagementRepository;
import com.dbcompanion.service.SchedulerManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller @RequestMapping("/db/scheduler/manage")
public class SchedulerManagementController {
    private final SchedulerManagementService service;
    public SchedulerManagementController(SchedulerManagementService service){this.service=service;}
    @GetMapping("/state") @ResponseBody public ResponseEntity<?> state(HttpServletRequest r){return call(r,s->{var state=service.state(s);return Map.of("state",state,"revision",OrdsManagement.digest(state));});}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody Request d,HttpServletRequest r){return call(r,s->service.preview(s,d));}
    @PostMapping("/apply") @ResponseBody public ResponseEntity<?> apply(@RequestBody Apply a,HttpServletRequest r){return call(r,s->service.apply(s,a));}
    private ResponseEntity<?> call(HttpServletRequest r,Function<PoolSession,Object> action){
        var http=r.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return error(401,"expired");
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.apply(session));}
        catch(Failure ex){return error(409,ex.getMessage());}
        catch(IllegalArgumentException|NullPointerException ex){return error(400,"invalid");}
        catch(RuntimeException ex){return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("schedulerManage.failed","Refresh state; do not retry automatically.")+" ("+OrdsManagementRepository.errorKey(ex)+")"));}
    }
    private ResponseEntity<?> error(int status,String key){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("schedulerManage."+key,key)));}
}
