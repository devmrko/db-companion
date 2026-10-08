package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiAssistant.Failure;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.AiAssistantService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class AiAssistantController {
    private final AiAssistantService service;
    public AiAssistantController(AiAssistantService service){this.service=service;}
    @GetMapping("/ai-assistant")
    public String page(HttpServletRequest request,Model model){
        var session=session(request);if(session==null)return "redirect:/login";
        synchronized(session){var metadata=session.metadata();model.addAttribute("info",metadata.info());model.addAttribute("schemas",metadata.schemas());model.addAttribute("selectedSchema",metadata.selectedSchema());}
        model.addAttribute("activePage","assistant");model.addAttribute("languageReturn","/ai-assistant");return "ai-assistant";
    }
    @GetMapping("/ai-assistant/options") @ResponseBody
    public ResponseEntity<?> options(@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest request){return run(request,false,()->service.options(session(request),refresh));}
    public record Choose(String name) {}
    public record Tokens(String name,String version,java.math.BigDecimal maxTokens,boolean persistent,boolean consent) {}
    @PostMapping("/ai-assistant/tokens") @ResponseBody
    public ResponseEntity<?> tokens(@RequestBody Tokens value,HttpServletRequest request){return run(request,false,()->{
        final Integer limit;
        try{limit=value.maxTokens()==null?null:value.maxTokens().intValueExact();}
        catch(ArithmeticException ex){throw new IllegalArgumentException("max_tokens must be an integer in range");}
        service.tokens(session(request),value.name(),value.version(),limit,value.persistent(),value.consent());return Map.of("saved",true);
    });}
    @PostMapping("/ai-assistant/selection") @ResponseBody
    public ResponseEntity<?> select(@RequestBody Choose choice,HttpServletRequest request){return run(request,false,()->{service.select(session(request),choice.name());return Map.of("saved",true);});}
    public record Prepare(String schema,String reference) {}
    @PostMapping("/db/functions/explain/preview") @ResponseBody
    public ResponseEntity<?> preview(@RequestBody Prepare value,Locale locale,HttpServletRequest request){return run(request,false,()->service.preview(session(request),value.schema(),value.reference(),locale));}
    public record Generate(String token,boolean consent) {}
    @PostMapping("/db/functions/explain") @ResponseBody
    public ResponseEntity<?> explain(@RequestBody Generate value,HttpServletRequest request){return run(request,true,()->service.explain(session(request),value.token(),value.consent()));}
    private ResponseEntity<?> run(HttpServletRequest request,boolean generating,Supplier<?> work){
        if(session(request)==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(Failure ex){return error(ex.status(),ex.getMessage());}
        catch(com.dbcompanion.common.exception.MetadataEditException ex){return error(ex.status(),ex.userMessage());}
        catch(com.dbcompanion.model.FunctionCatalog.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(IllegalArgumentException ex){return error(400,UiMessages.text("assistant.invalid","요청 정보를 확인해 주세요."));}
        catch(RuntimeException ex){
            // Oracle messages can contain source/prompt/provider response. Expose only the code.
            String code=CredentialCatalogRepository.error(ex);
            return error(503,UiMessages.text(generating?"assistant.callError":"assistant.loadError",generating?
                    "설명 생성 결과를 확인하지 못했습니다. 사용량이 발생했을 수 있으며 자동 재시도하지 않았습니다.":"AI 도우미 정보를 조회하지 못했습니다. 프로필·권한을 확인해 주세요.")+" · "+code);
        }
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
    private PoolSession session(HttpServletRequest request){var http=request.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
}
