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
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(service.message(s,input,locale));}
        catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(com.dbcompanion.common.exception.MetadataEditException ex){return error(ex.status(),ex.userMessage());}
        catch(SecurityException ex){return error(403,"이 계정에서 허용된 대상의 안내만 조회할 수 있습니다.");}
        catch(com.dbcompanion.service.AiConversationProtocol.ResponseFailure ex){return ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of(
            "error","AI의 서비스 응답 형식을 확인하지 못했습니다. 자동 재시도하지 않았습니다.","diagnostic",Map.of("code",ex.getMessage(),"modelCalls",ex.modelCalls(),"executions",ex.executions(),"response",ex.response())));}
        catch(IllegalArgumentException ex){
            String diagnostic=ex.getMessage()!=null&&ex.getMessage().matches("CONVERSATION_[A-Z_]+")?ex.getMessage():"CONVERSATION_INVALID";
            return ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of("error","대화 요청 또는 AI의 서비스 응답을 확인하지 못했습니다. 적용하지 않았으며 자동 재시도하지 않았습니다.","diagnostic",diagnostic));}
        catch(RuntimeException ex){
            // Provider errors may contain prompts or customer metadata; record types/locations only.
            LOG.warn("Conversation failed: type={}, frames={}",ex.getClass().getName(),Arrays.stream(ex.getStackTrace()).limit(5).map(e->e.getClassName()+"."+e.getMethodName()+":"+e.getLineNumber()).toList());
            return error(503,"AI 대화 결과를 확인하지 못했습니다. 사용량이 발생했을 수 있으며 자동 재시도하지 않았습니다. · "+CredentialCatalogRepository.error(ex));}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
}
