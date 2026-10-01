package com.dbcompanion.model;

import java.time.*;
import java.util.*;
import com.dbcompanion.service.OntologyQueryService;

public final class AuditHistory {
    private AuditHistory(){}
    public enum Source { original, archive }
    public enum Operation { INSTALL, CONFIGURE, PAUSE, RESUME, RUN }
    public record Query(Source source,LocalDate from,LocalDate to,String scope,String dbUser,String endUser,String objectOwner,String objectName,String outcome,String context,int page) {
        public Query {
            if(source==null||from==null||to==null||to.isBefore(from)||to.isAfter(from.plusDays(365))||page<1||page>1000)throw new IllegalArgumentException("Invalid audit range");
            if(!Set.of("dds","all").contains(scope)||!Set.of("all","success","failure").contains(outcome))throw new IllegalArgumentException("Invalid audit filter");
            for(var value:List.of(dbUser,endUser,objectOwner,objectName,context))if(value.length()>256||value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid audit filter");
            if(!context.isEmpty()&&!context.matches("[a-fA-F0-9]{2,128}"))throw new IllegalArgumentException("Invalid security context");
            context=context.toUpperCase(Locale.ROOT);
        }
    }
    public record Settings(int minutes,int retentionDays) {
        public Settings {if(minutes<5||minutes>1440||retentionDays<0||retentionDays>3650)throw new IllegalArgumentException("Invalid collection settings");}
    }
    public record Status(String owner,String database,String source,String sourceState,String store,String packageState,String jobState,boolean enabled,
                         boolean canInstall,List<String> missingPrivileges,Map<String,Object> config,Map<String,Object> job,String error) {
        public Status {missingPrivileges=List.copyOf(missingPrivileges);config=Collections.unmodifiableMap(new LinkedHashMap<>(config));job=Collections.unmodifiableMap(new LinkedHashMap<>(job));}
        public boolean ready(){return "READY".equals(store)&&"READY".equals(packageState)&&"READY".equals(jobState);}
    }
    public record Preview(String token,Operation operation,Settings settings,String fingerprint,Instant expires,List<String> statements) {
        public Preview {statements=List.copyOf(statements);}
    }
    public record Page(List<Map<String,Object>> rows,int page,boolean more){public Page {rows=List.copyOf(rows);}}
    public record Selection(Query query,Map<String,Object> row){public Selection {row=Collections.unmodifiableMap(new LinkedHashMap<>(row));}}
    public static String fingerprint(Status s){
        // Exclude asynchronous counters and scheduler execution state.
        return OntologyQueryService.hash(s.owner()+"|"+s.database()+"|"+s.source()+"|"+s.sourceState()+"|"+s.store()+"|"+s.packageState()+"|"+s.jobState()+"|"+s.enabled()+"|"+s.config().get("INTERVAL_MINUTES")+"|"+s.config().get("RETENTION_DAYS"));
    }
}
