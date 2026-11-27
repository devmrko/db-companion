package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.service.AiConversationService;
import com.dbcompanion.repository.CredentialCatalogRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai-setup/conversation")
public class AiConversationController {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AiConversationController.class);
    private final AiConversationService service;
    public AiConversationController(AiConversationService service){this.service=service;}
    @GetMapping("/catalogue") public ResponseEntity<?> catalogue(HttpServletRequest request){
        var http=request.getSession(false);if(http==null||http.getAttribute(PoolSession.ATTRIBUTE)==null)return error(401,"로그인 세션이 만료되었습니다.");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("menus",AiConversationService.menus(),"services",AiConversationService.tools(),"limits",Map.of("modelCallsPerMessage",7,"workspaceOperationsPerMessage",1,"databaseWriteTools",0)));
    }
    @PostMapping("/message") public ResponseEntity<?> message(@RequestBody AiConversationService.Input input,Locale locale,HttpServletRequest request){
        var http=request.getSession(false);var s=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(s==null)return error(401,"로그인 세션이 만료되었습니다.");
        String requestId=UUID.randomUUID().toString();
        try{return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Request-ID",requestId).body(service.message(s,input,locale));}
        catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(com.dbcompanion.common.exception.MetadataEditException ex){return error(ex.status(),ex.userMessage());}
        catch(SecurityException ex){return error(403,"이 계정에서 허용된 대상의 안내만 조회할 수 있습니다.");}
        catch(com.dbcompanion.service.AiConversationProtocol.ResponseFailure ex){
            String code=code(ex);LOG.warn("Conversation rejected: requestId={}, code={}, tool={}, modelCalls={}",requestId,code,ex.tool(),ex.modelCalls());
            return failure(400,"AI의 서비스 요청을 확인하지 못했습니다. 적용하지 않았으며 자동 재시도하지 않았습니다.",requestId,
                Map.of("code",code,"tool",ex.tool(),"modelCalls",ex.modelCalls(),"executions",ex.executions(),"response",ex.response()));}
        catch(IllegalArgumentException ex){
            String code=code(ex);LOG.warn("Conversation rejected: requestId={}, code={}",requestId,code);
            return failure(400,"대화 요청 또는 AI의 서비스 응답을 확인하지 못했습니다. 적용하지 않았으며 자동 재시도하지 않았습니다.",requestId,Map.of("code",code));}
        catch(RuntimeException ex){
            // Provider errors may contain prompts or customer metadata; record types/locations only.
            LOG.warn("Conversation failed: requestId={}, type={}, frames={}",requestId,ex.getClass().getName(),Arrays.stream(ex.getStackTrace()).limit(5).map(e->e.getClassName()+"."+e.getMethodName()+":"+e.getLineNumber()).toList());
            return failure(503,"AI 대화 결과를 확인하지 못했습니다. 사용량이 발생했을 수 있으며 자동 재시도하지 않았습니다. · "+CredentialCatalogRepository.error(ex),requestId,Map.of("code","CONVERSATION_BACKEND"));}
    }
    private static String code(IllegalArgumentException ex){return ex.getMessage()!=null&&ex.getMessage().matches("CONVERSATION_[A-Z_]+")?ex.getMessage():"CONVERSATION_INVALID";}
    private ResponseEntity<?> failure(int status,String message,String requestId,Map<String,Object> values){var diagnostic=new LinkedHashMap<>(values);diagnostic.put("requestId",requestId);return ResponseEntity.status(status).header("Cache-Control","no-store").header("X-Request-ID",requestId).body(Map.of("error",message,"diagnostic",diagnostic));}
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
}
