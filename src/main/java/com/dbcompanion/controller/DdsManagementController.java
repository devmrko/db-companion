package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.DdsManagement.*;
import com.dbcompanion.model.OrdsManagement;
import com.dbcompanion.model.DdsIdentity;
import com.dbcompanion.repository.OrdsManagementRepository;
import com.dbcompanion.service.DdsManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/db/security/manage")
public class DdsManagementController {
    private final DdsManagementService service;
    private com.dbcompanion.service.DdsIdentityProbeService probe;
    private com.dbcompanion.service.DdsRuntimeProbeService runtimeProbe;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRuntimeProbe(com.dbcompanion.service.DdsRuntimeProbeService runtimeProbe){this.runtimeProbe=runtimeProbe;}
    @org.springframework.beans.factory.annotation.Autowired
    public void setProbe(com.dbcompanion.service.DdsIdentityProbeService probe){this.probe=probe;}
    public DdsManagementController(DdsManagementService service){this.service=service;}
    @GetMapping("/state") @ResponseBody public ResponseEntity<?> state(HttpServletRequest r){return call(r,s->{var state=service.state(s);return Map.of("state",state,"revision",OrdsManagement.digest(state));});}
    @PostMapping("/preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody PreviewRequest d,HttpServletRequest r){return call(r,s->service.preview(s,d));}
    @PostMapping("/apply") @ResponseBody public ResponseEntity<?> apply(@RequestBody Apply a,HttpServletRequest r){return call(r,s->service.apply(s,a));}
    @GetMapping("/identities") @ResponseBody public ResponseEntity<?> identities(HttpServletRequest r){return call(r,s->{var state=service.identities(s);return Map.of("state",state,"revision",OrdsManagement.digest(state));});}
    @PostMapping("/identity-preview") @ResponseBody public ResponseEntity<?> identityPreview(@RequestBody DdsIdentity.Request d,HttpServletRequest r){return call(r,s->service.identityPreview(s,d));}
    @PostMapping("/identity-apply") @ResponseBody public ResponseEntity<?> identityApply(@RequestBody DdsIdentity.Apply a,HttpServletRequest r){return call(r,s->service.identityApply(s,a));}
    @PostMapping("/identity-probe") @ResponseBody public ResponseEntity<?> identityProbe(@RequestBody com.dbcompanion.service.DdsIdentityProbeService.Request a,HttpServletRequest r){return call(r,s->probe.probe(s,a));}
    @PostMapping("/runtime-probe") @ResponseBody public ResponseEntity<?> runtimeProbe(@RequestBody com.dbcompanion.service.DdsRuntimeProbeService.Request a,HttpServletRequest r){return call(r,s->runtimeProbe.probe(s,a));}
    @GetMapping("/runtime-readiness") @ResponseBody public ResponseEntity<?> runtimeReadiness(HttpServletRequest r){return call(r,service::runtimeReadiness);}
    private ResponseEntity<?> call(HttpServletRequest r,Function<PoolSession,Object> action){
        var http=r.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return error(401,"expired");
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.apply(session));}
        catch(Failure ex){return error(409,ex.getMessage());}
        catch(IllegalArgumentException|NullPointerException ex){return error(400,"invalid");}
        catch(RuntimeException ex){String code=OrdsManagementRepository.errorKey(ex);return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("ddsManage.failed","Refresh state; no automatic retry.")+" ("+code+") "+UiMessages.text("ddsManage.adminHelp","Ask ADMIN to review DDS privileges.")));}
    }
    private ResponseEntity<?> error(int status,String key){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("ddsManage."+key,key)));}
}
