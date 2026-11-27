package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import com.dbcompanion.common.i18n.UiMessages;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Read-only instructions: no grant executor and no user-controlled target. */
@Controller
public class AdminHelpController {
    public static List<com.dbcompanion.service.AdminHelpService.Instruction> instructions(String owner){return com.dbcompanion.service.AdminHelpService.instructions(owner);}
    public static com.dbcompanion.service.AdminHelpService.Advice advice(String login,String target,String topic){return com.dbcompanion.service.AdminHelpService.advice(login,target,topic);}
    @GetMapping("/db/admin-help/advice") @ResponseBody
    public ResponseEntity<?> guidance(@RequestParam(defaultValue="profile") String topic,@RequestParam(defaultValue="") String target,HttpServletRequest request){
        var http=request.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return ResponseEntity.status(401).header("Cache-Control","no-store").body(Map.of("error","Login required"));
        String login=session.metadata().info().username();
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(advice(login,target.isBlank()?login:target,topic));}
        catch(SecurityException ex){return ResponseEntity.status(403).header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("adminAdvice.self","일반 계정은 자신의 권한 안내만 조회할 수 있습니다.")));}
        catch(IllegalArgumentException ex){return ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of("error",UiMessages.text("adminAdvice.invalid","대상 계정과 기능을 다시 선택하세요.")));}
    }
    @GetMapping("/db/admin-help")
    public String page(HttpServletRequest request,Model model){
        var http=request.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return "redirect:/login";
        model.addAttribute("info",session.metadata().info());
        model.addAttribute("instructions",instructions(session.metadata().info().username()));
        model.addAttribute("activePage","admin-help");model.addAttribute("languageReturn","/db/admin-help");
        return "admin-help";
    }
}
