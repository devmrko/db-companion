package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.service.AiSetupAssistantService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-setup/assistant")
public class AiSetupAssistantController {
    private final AiSetupAssistantService service;
    public AiSetupAssistantController(AiSetupAssistantService service){this.service=service;}
    public record Run(String token,boolean consent) {}
    @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody AiSetupAssistantService.Input input,Locale locale,HttpServletRequest r){return respond(r,s->service.preview(s,input,locale));}
    @PostMapping("/analyze") public ResponseEntity<?> analyze(@RequestBody Run input,HttpServletRequest r){return respond(r,s->service.analyze(s,input.token(),input.consent()));}
    @PostMapping("/change-preview") public ResponseEntity<?> changePreview(HttpServletRequest r){return respond(r,service::changePreview);}
    @PostMapping("/apply") public ResponseEntity<?> apply(@RequestBody Run input,HttpServletRequest r){return respond(r,s->service.apply(s,input.token(),input.consent()));}
    private ResponseEntity<?> respond(HttpServletRequest r,Function<PoolSession,Object> action) {
        var http=r.getSession(false);var s=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(s==null)return error(401,"로그인 세션이 만료되었습니다.");
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.apply(s));}
        catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(MetadataEditException ex){return error(ex.status(),ex.userMessage());}
        catch(IllegalArgumentException ex){return error(400,"요청 또는 AI 응답 형식을 확인할 수 없습니다. 변경하지 않았습니다.");}
        catch(RuntimeException ex){return error(503,"처리 결과를 확인하지 못했습니다. 설정과 이력을 확인하세요. 자동 재시도하지 않았습니다.");}
    }
    private ResponseEntity<?> error(int status,String text){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",text));}
}
