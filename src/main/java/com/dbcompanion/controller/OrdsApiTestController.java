package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.OrdsApiTest.*;
import com.dbcompanion.model.OrdsManagement.Failure;
import com.dbcompanion.service.OrdsApiTestService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/db/ords/test")
public class OrdsApiTestController {
    private final OrdsApiTestService service;
    public OrdsApiTestController(OrdsApiTestService service){this.service=service;}
    @PostMapping("/prepare") public ResponseEntity<?> prepare(@RequestBody Selection input,HttpServletRequest request){return invoke(request,s->service.prepare(s,input));}
    @PostMapping("/run") public ResponseEntity<?> run(@RequestBody Request input,HttpServletRequest request){return invoke(request,s->service.run(s,input));}
    private ResponseEntity<?> invoke(HttpServletRequest request,Function<PoolSession,?> action){
        var http=request.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return error(401,"expired");
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.apply(session));}
        catch(Failure ex){return error(ex.status(),ex.getMessage());}
        catch(RuntimeException ex){return error(503,"test.failed");}
    }
    private ResponseEntity<?> error(int status,String code){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("ords."+code,code),"code",code));}
}
