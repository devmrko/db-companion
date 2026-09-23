package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.CatalogColumnProbe.*;
import com.dbcompanion.service.CatalogColumnProbeService;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@Profile("catalog-diagnostics")
@RequestMapping("/db/external-sources/catalog-column-probe")
public class CatalogColumnProbeController {
    private static final String STATE="catalogColumnProbeResults";
    private final CatalogColumnProbeService service;
    public CatalogColumnProbeController(CatalogColumnProbeService service){this.service=service;}
    private PoolSession session(HttpServletRequest request){var http=request.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
    private State state(HttpServletRequest request){
        var http=request.getSession(false);var state=(State)http.getAttribute(STATE);
        if(state==null){state=new State();http.setAttribute(STATE,state);}return state;
    }
    @GetMapping
    public String page(HttpServletRequest request,HttpServletResponse response,Model model){
        response.setHeader("Cache-Control","no-store");var session=session(request);if(session==null)return "redirect:/login";
        synchronized(session){
            boolean allowed=service.target().allowed(session.metadata().info().username());
            model.addAttribute("allowed",allowed);
            if(!allowed){response.setStatus(403);return "catalog-column-probe";}
            var state=state(request);model.addAttribute("target",service.target());model.addAttribute("results",state.results());
            model.addAttribute("steps",java.util.Arrays.stream(Step.values()).filter(step->state.results().stream().noneMatch(result->result.step()==step)).toList());
        }
        return "catalog-column-probe";
    }
    @PostMapping
    public String inspect(@RequestParam Step step,HttpServletRequest request,HttpServletResponse response,Model model){
        var session=session(request);if(session==null)return "redirect:/login";
        synchronized(session){
            if(!service.target().allowed(session.metadata().info().username()))return page(request,response,model);
            state(request).once(step,()->service.inspect(session,step));
        }
        return "redirect:/db/external-sources/catalog-column-probe";
    }
}
