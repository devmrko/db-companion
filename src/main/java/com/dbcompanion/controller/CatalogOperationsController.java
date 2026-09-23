package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.CatalogOperations.Level;
import com.dbcompanion.model.ExternalSources.Failure;
import com.dbcompanion.repository.ExternalSourcesRepository;
import com.dbcompanion.service.CatalogOperationsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/db/external-sources/catalogs")
public class CatalogOperationsController {
    private final CatalogOperationsService service;
    public CatalogOperationsController(CatalogOperationsService service){this.service=service;}
    public record Draft(String owner,String link,String catalog) {}
    public record Confirmation(String token) {}
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody Draft body,HttpServletRequest request){
        return respond(request,()->service.preview(session(request),body.owner(),body.link(),body.catalog()));
    }
    @PostMapping("/mount")
    public ResponseEntity<?> mount(@RequestBody Confirmation body,HttpServletRequest request){
        return respond(request,()->service.mount(session(request),body.token()));
    }
    @GetMapping("/browse")
    public ResponseEntity<?> browse(@RequestParam String name,@RequestParam Level level,@RequestParam(required=false) String schemaName,
            @RequestParam(required=false) String tableName,HttpServletRequest request){
        return respond(request,()->service.browse(session(request),name,level,schemaName,tableName));
    }
    private ResponseEntity<?> respond(HttpServletRequest request,Supplier<?> action){
        if(session(request)==null)return error(401,UiMessages.text("ui.b9c067f345b1","로그인 세션이 만료되었습니다. 다시 로그인해 주세요."));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}
        catch(Failure ex){return error(ex.status(),ex.getMessage());}
        catch(IllegalArgumentException ex){return error(400,UiMessages.text("catalogOps.invalid","이름·선택 항목·확인 유효시간을 확인해 주세요. 카탈로그명은 영문자로 시작하는 영문·숫자·밑줄 128자 이내입니다."));}
        catch(UnsupportedOperationException ex){return error(409,UiMessages.text("catalogOps.unsupported","이 DB의 카탈로그 API·메타데이터 형식 지원과 실행 권한을 확인해 주세요."));}
        catch(com.dbcompanion.repository.CatalogOperationsRepository.UnsupportedShape ex){return error(409,UiMessages.text("catalogOps.shape","메타데이터 형식을 확인해 주세요. 반환 필드:")+" "+ex.getMessage());}
        catch(com.dbcompanion.repository.CatalogOperationsRepository.LimitExceeded ex){return error(409,UiMessages.text("catalogOps.limit","메타데이터 조회 한도를 초과했습니다. 일부 결과로 표시하지 않습니다."));}
        catch(RuntimeException ex){return error(503,UiMessages.text("catalogOps.error","카탈로그 처리 오류. 등록 목록·API 지원·실행 권한을 확인해 주세요.")+" · "+ExternalSourcesRepository.error(ex));}
    }
    private ResponseEntity<?> error(int status,String text){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",text));}
    private PoolSession session(HttpServletRequest request){var http=request.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
}
