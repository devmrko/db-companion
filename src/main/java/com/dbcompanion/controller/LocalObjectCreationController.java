package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.ObjectCreationException;
import com.dbcompanion.model.LocalObjectCreation;
import com.dbcompanion.model.LocalObjectCreation.*;
import com.dbcompanion.service.LocalObjectCreationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/db/objects/create")
public class LocalObjectCreationController {
    private static final String PLAN=LocalObjectCreationController.class.getName()+".plan";
    private record Pending(Plan plan,PoolSession session) {}
    private final LocalObjectCreationService service;
    public LocalObjectCreationController(LocalObjectCreationService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody Input input,HttpServletRequest r){return respond(r,()->{
        r.getSession().removeAttribute(PLAN);var plan=service.preview(session(r),input);r.getSession().setAttribute(PLAN,new Pending(plan,session(r)));return plan;
    });}
    @PostMapping("/apply") public ResponseEntity<?> apply(@RequestBody Apply input,HttpServletRequest r){return respond(r,()->{
        var s=session(r);var pending=(Pending)r.getSession().getAttribute(PLAN);
        if(pending==null||pending.session()!=s)throw new IllegalArgumentException("Database session changed");
        var plan=pending.plan();
        LocalObjectCreation.require(plan,input,s.metadata().info().username(),s.metadata().selectedSchema());
        r.getSession().removeAttribute(PLAN);
        return service.create(s,plan,input.secret());
    });}
    private ResponseEntity<?> respond(HttpServletRequest r,Supplier<?> action){
        var s=session(r);if(s==null)return error(401,"Session expired");
        synchronized(s){try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}
            catch(RuntimeException ex){throw ObjectCreationException.from(ex,"VALIDATION",false);}}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
}
