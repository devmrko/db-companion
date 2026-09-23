package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.service.OntologyArchiveService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ontology-query/archive")
public class OntologyArchiveController {
    private final OntologyArchiveService service;
    public OntologyArchiveController(OntologyArchiveService service){this.service=service;}
    public record Install(String schema,boolean confirmed){}
    public record Setup(String schema,String tablespace,boolean confirmed){}
    public record Preview(String id,String route){}
    public record Save(String token,boolean confirmed){}
    @GetMapping("/status") public ResponseEntity<?> status(@RequestParam String schema,@RequestParam(defaultValue="false") boolean refresh,HttpServletRequest r){return run(r,()->service.status(session(r),schema,refresh));}
    @PostMapping("/install") public ResponseEntity<?> install(@RequestBody Install value,HttpServletRequest r){return run(r,()->service.installRecords(session(r),value.schema(),value.confirmed()));}
    @GetMapping("/setup-preview") public ResponseEntity<?> setupPreview(@RequestParam String schema,HttpServletRequest r){return run(r,()->service.setupPreview(session(r),schema));}
    @PostMapping("/setup") public ResponseEntity<?> setup(@RequestBody Setup value,HttpServletRequest r){return run(r,()->service.installRdf(session(r),value.schema(),value.tablespace(),value.confirmed()));}
    @PostMapping("/preview") public ResponseEntity<?> preview(@RequestBody Preview value,HttpServletRequest r){return run(r,()->service.preview(session(r),value.id(),value.route()));}
    @PostMapping("/save") public ResponseEntity<?> save(@RequestBody Save value,HttpServletRequest r){return run(r,()->service.save(session(r),value.token(),value.confirmed()));}
    @GetMapping("/list") public ResponseEntity<?> list(@RequestParam String schema,@RequestParam(defaultValue="") String before,HttpServletRequest r){return run(r,()->service.page(session(r),schema,before));}
    @GetMapping("/detail") public ResponseEntity<?> detail(@RequestParam String schema,@RequestParam String id,HttpServletRequest r){return run(r,()->service.detail(session(r),schema,id));}
    @GetMapping("/graph") public ResponseEntity<?> graph(@RequestParam String schema,@RequestParam String id,HttpServletRequest r){return run(r,()->service.graph(session(r),schema,id));}
    private PoolSession session(HttpServletRequest r){var h=r.getSession(false);return h==null?null:(PoolSession)h.getAttribute(PoolSession.ATTRIBUTE);}
    private ResponseEntity<?> run(HttpServletRequest r,Supplier<?> action){
        if(session(r)==null)return error(401,UiMessages.text("ui.b9c067f345b1","Login required"));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(action.get());}
        catch(Ontology.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(OntologyArchiveService.OperationFailure ex){return error(503,ex.getMessage());}
        catch(RuntimeException ex){return error(503,UiMessages.text("ontology.archive.error","Archive request error")+" · "+OntologyArchiveService.detail(ex));}
    }
    private ResponseEntity<?> error(int code,String text){return ResponseEntity.status(code).header("Cache-Control","no-store").body(Map.of("error",text));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<?> invalid(){return error(400,UiMessages.text("ontology.archive.invalid","Invalid request"));}
}
