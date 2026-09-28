package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.BusinessGlossary;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.BusinessGlossaryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/business-glossary")
public class BusinessGlossaryController {
    private final BusinessGlossaryService service;
    public BusinessGlossaryController(BusinessGlossaryService service){this.service=service;}
    private PoolSession session(HttpServletRequest r){var s=r.getSession(false);return s==null?null:(PoolSession)s.getAttribute(PoolSession.ATTRIBUTE);}
    @GetMapping public String page(HttpServletRequest r,Model model){var s=session(r);if(s==null)return "redirect:/login";model.addAttribute("info",s.metadata().info());model.addAttribute("activePage","business-glossary");model.addAttribute("languageReturn","/business-glossary");return "business-glossary";}
    @GetMapping("/status") @ResponseBody public ResponseEntity<?> status(HttpServletRequest r){return respond(r,()->service.status(session(r)));}
    @GetMapping("/history/status") @ResponseBody public ResponseEntity<?> historyStatus(HttpServletRequest r){return respond(r,()->Map.of("storage",service.historyStatus(session(r))));}
    @GetMapping("/history") @ResponseBody public ResponseEntity<?> history(@RequestParam String id,@RequestParam(defaultValue="") String before,HttpServletRequest r){return respond(r,()->service.history(session(r),id,before));}
    @GetMapping("/list") @ResponseBody public ResponseEntity<?> list(@RequestParam(defaultValue="") String filter,@RequestParam(defaultValue="0") int offset,HttpServletRequest r){return respond(r,()->service.page(session(r),filter,offset));}
    public record Save(String id,long revision,BusinessGlossary.Draft value,boolean consent) {}
    @PostMapping("/save") @ResponseBody public ResponseEntity<?> save(@RequestBody Save v,HttpServletRequest r){return respond(r,()->service.save(session(r),v.id(),v.revision(),v.value(),v.consent()));}
    public record SetupRequest(String operation) {}
    @PostMapping("/setup-preview") @ResponseBody public ResponseEntity<?> preview(@RequestBody SetupRequest v,HttpServletRequest r){return respond(r,()->service.setupPreview(session(r),v.operation()));}
    public record Apply(String token,boolean consent) {}
    @PostMapping("/setup") @ResponseBody public ResponseEntity<?> setup(@RequestBody Apply v,HttpServletRequest r){return respond(r,()->service.setup(session(r),v.token(),v.consent()));}
    public record SearchRequest(String question,String profile,boolean useText) {}
    @PostMapping("/search") @ResponseBody public ResponseEntity<?> search(@RequestBody SearchRequest v,HttpServletRequest r){return respond(r,()->service.search(session(r),v.question(),v.profile(),v.useText()));}
    private ResponseEntity<?> respond(HttpServletRequest r,Supplier<?> work){
        if(session(r)==null)return error(401,"로그인 세션이 만료되었습니다.");
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(AiAssistant.Failure e){return error(e.status(),e.getMessage());}
        catch(IllegalArgumentException e){return error(400,"요청 정보를 확인해 주세요.");}
        catch(RuntimeException e){return error(503,"업무 용어 사전 요청을 확인하지 못했습니다. 조회 실패는 검색 결과 없음이 아닙니다. 쓰기 결과는 새로고침으로 확인하고 자동 재시도하지 마세요. · "+CredentialCatalogRepository.error(e));}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
}
