package com.dbcompanion.controller;

import com.dbcompanion.common.db.MetadataSql;
import com.dbcompanion.common.db.PoolSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
