package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.GlossaryTransfer;
import com.dbcompanion.service.GlossaryTransferService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/business-glossary/transfer")
public class GlossaryTransferController {
    private static final String PREVIEW=GlossaryTransferController.class.getName();
    private record Held(PoolSession owner, GlossaryTransfer.Preview preview) {}
    private final GlossaryTransferService service;
    public GlossaryTransferController(GlossaryTransferService service){this.service=service;}
    private PoolSession session(HttpServletRequest request){var http=request.getSession(false);return http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);}
    @GetMapping("/export")
    public ResponseEntity<?> export(HttpServletRequest request){return respond(request,()->service.export(session(request)));}
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody String content,HttpServletRequest request){return respond(request,()->{
        var s=session(request);request.getSession().removeAttribute(PREVIEW);
        var preview=service.preview(s,content);request.getSession().setAttribute(PREVIEW,new Held(s,preview));return preview;
    });}
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody GlossaryTransfer.Apply apply,HttpServletRequest request){return respond(request,()->{
        var s=session(request);Object held=request.getSession().getAttribute(PREVIEW);
        request.getSession().removeAttribute(PREVIEW); // One attempt; no automatic retry after uncertain writes.
        var preview=held instanceof Held h&&h.owner()==s?h.preview():null;
        return Map.of("saved",service.apply(s,preview,apply));
    });}
    private ResponseEntity<?> respond(HttpServletRequest request,Supplier<?> work){
        var s=session(request);if(s==null)return error(401,"로그인 세션이 만료되었습니다.");
        synchronized(s){try{return ResponseEntity.ok().header("Cache-Control","no-store").body(work.get());}
        catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(IllegalArgumentException ex){return error(400,"가져올 파일과 선택 항목을 확인해 주세요.");}
        catch(RuntimeException ex){return error(503,"요청을 완료하지 못했습니다. 자동 재시도하지 말고 사전을 새로고침해 저장 상태를 확인하세요.");}}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
}
