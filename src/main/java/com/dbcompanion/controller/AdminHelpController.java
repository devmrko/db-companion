package com.dbcompanion.controller;

import com.dbcompanion.common.db.MetadataSql;
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
    public record Instruction(String key,String sql) {}
    public static List<Instruction> instructions(String owner){
        String target=MetadataSql.identifier(owner);
        return List.of(
            new Instruction("table","GRANT CREATE TABLE TO "+target+";"),
            new Instruction("function","GRANT CREATE PROCEDURE TO "+target+";"),
            new Instruction("text","GRANT EXECUTE ON CTXSYS.CTX_DDL TO "+target+";"),
            new Instruction("credential","GRANT EXECUTE ON DBMS_CLOUD TO "+target+";"),
            new Instruction("profile","GRANT EXECUTE ON DBMS_CLOUD_AI TO "+target+";"),
            new Instruction("agent","GRANT EXECUTE ON DBMS_CLOUD_AI_AGENT TO "+target+";"),
            new Instruction("job","GRANT CREATE JOB TO "+target+";"),
            new Instruction("graph","GRANT CREATE PROPERTY GRAPH, CREATE VIEW TO "+target+";"),
            new Instruction("rdf","GRANT CREATE TABLE, CREATE VIEW, CREATE PROCEDURE, CREATE SEQUENCE, CREATE TRIGGER, CREATE TYPE TO "+target+";"));
    }
    public record Advice(String target,String actor,String privilegeStatus,String purpose,String steps,List<Instruction> instructions,List<String> verification,String link) {}
    public static Advice advice(String login,String target,String topic){
        if(target==null||!target.matches("[A-Z][A-Z0-9_$#]{0,124}"))throw new IllegalArgumentException("Invalid target");
        if(!login.equals("ADMIN")&&!login.equals(target))throw new SecurityException("Own account only");
        var requirements=Map.of("profile",List.of("profile"),"credential",List.of("credential"),"text",List.of("table","function","text"),
                "semantic",List.<String>of(),"metadata-history",List.<String>of(),"profile-history",List.of("table"),
                "network",List.<String>of(),"dds",List.<String>of(),"agent",List.of("agent"),"table",List.of("table"));
        var keys=requirements.get(topic);if(keys==null)throw new IllegalArgumentException("Unknown help topic");
        String title=switch(topic){case "semantic"->"semanticTitle";case "metadata-history"->"historyTitle";case "profile-history"->"profileHistoryTitle";
            case "network","dds"->"adminOnly";default->topic;};
        String steps=switch(topic){case "semantic"->"semanticSteps";case "metadata-history"->"historySteps";case "profile-history"->"profileHistorySteps";default->"steps";};
        String link=switch(topic){case "semantic"->"/db/admin-help#semantic-access";case "metadata-history"->"/tables";case "profile-history","profile"->"/ai-profiles";
            case "network"->"/db/accounts#ai-network";case "dds"->"/db/security";default->"/db/admin-help";};
        return new Advice(target,"ADMIN","NOT_CHECKED",UiMessages.text("adminHelp."+title,title),
                topic.equals("network")?UiMessages.text("aiNetwork.remedy","aiNetwork.remedy"):
                topic.equals("dds")?UiMessages.text("adminAdvice.dds","DDS의 계정·정책·권한 상태를 ADMIN으로 확인한 뒤 필요한 권한의 미리보기를 사용하세요."):
                UiMessages.text("adminHelp."+steps,steps),instructions(target).stream().filter(v->keys.contains(v.key())).toList(),List.of(
                    "SELECT GRANTEE, PRIVILEGE FROM DBA_SYS_PRIVS WHERE GRANTEE = '"+target+"' ORDER BY PRIVILEGE;",
                    "SELECT GRANTEE, OWNER, TABLE_NAME, PRIVILEGE FROM DBA_TAB_PRIVS WHERE GRANTEE = '"+target+"' ORDER BY OWNER, TABLE_NAME, PRIVILEGE;"),link);
    }
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
