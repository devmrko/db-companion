package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.service.AuditHistoryService;
import com.dbcompanion.service.DeepDataSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Capability is observed per login, not inferred from ADMIN or schema name. */
@RestController
public class FeatureAccessController {
    private final DeepDataSecurityService dds;
    private final AuditHistoryService audit;
    private record Snapshot(PoolSession owner,Instant expires,Map<String,?> value) {}
    public FeatureAccessController(DeepDataSecurityService dds,AuditHistoryService audit){this.dds=dds;this.audit=audit;}
    @GetMapping("/app/feature-access") public ResponseEntity<?> access(HttpServletRequest request){
        var http=request.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return ResponseEntity.status(401).body(Map.of("error","Login required"));
        synchronized(session){
            String key=FeatureAccessController.class.getName();var cached=http.getAttribute(key);
            if(cached instanceof Snapshot snapshot&&snapshot.owner()==session&&Instant.now().isBefore(snapshot.expires()))return response(snapshot.value());
            Map<String,String> security;String original="UNKNOWN",archive="UNKNOWN";
            try{security=dds.capabilities(session);}catch(RuntimeException ex){security=Map.of();}
            try{var value=audit.status(session);original=value.sourceState();archive=value.store();
                if("UNAVAILABLE".equals(original)&&!value.error().matches("(?s).*ORA-(00942|01031).*"))original="UNKNOWN";
            }catch(RuntimeException ex){original="UNKNOWN";archive="UNKNOWN";}
            var value=Map.of("dds",security,"auditOriginal",original,"auditArchive",archive,"checkedAt",Instant.now().toString());
            http.setAttribute(key,new Snapshot(session,Instant.now().plusSeconds(60),value));return response(value);
        }
    }
    private ResponseEntity<?> response(Map<String,?> value){return ResponseEntity.ok().header("Cache-Control","no-store").body(value);}
}
