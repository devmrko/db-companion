package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SemanticVersionPageController {
    @GetMapping("/semantic-versions")
    public String page(HttpServletRequest request,Model model){
        var http=request.getSession(false);
        var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return "redirect:/login";
        model.addAttribute("info",session.metadata().info());
        model.addAttribute("activePage","semantic-versions");
        model.addAttribute("languageReturn","/semantic-versions");
        return "semantic-versions";
    }
}
