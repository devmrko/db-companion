package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.service.ProfilePreflightService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
public class ProfilePreflightController {
    private final ProfilePreflightService service;
    public ProfilePreflightController(ProfilePreflightService service) { this.service=service; }
    public record Request(String schema,String profile) {}
    @PostMapping("/ai-profiles/preflight") @ResponseBody
    public ResponseEntity<?> check(@RequestBody Request body,HttpServletRequest request) {
        var http=request.getSession(false); var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return ResponseEntity.status(401).body(Map.of("error","Login session expired"));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(service.check(session,body.schema(),body.profile()));}
        catch(IllegalArgumentException ex){return ResponseEntity.status(409).body(Map.of("error",ex.getMessage()));}
        catch(RuntimeException ex){return ResponseEntity.status(503).body(Map.of("error","Preflight could not be completed; access is unknown."));}
    }
}
