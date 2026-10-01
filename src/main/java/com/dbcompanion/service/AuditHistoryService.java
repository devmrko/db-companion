package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.AuditHistory.*;
import com.dbcompanion.repository.AuditHistoryRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class AuditHistoryService {
    private final SessionDataSource source;private final AuditHistoryRepository repository;
    public AuditHistoryService(SessionDataSource source,AuditHistoryRepository repository){this.source=source;this.repository=repository;}
    private <T>T bound(PoolSession s,Supplier<T> action){synchronized(s){source.bind(s.pool(),s.metadata().info().username());try{return action.get();}finally{source.clear();}}}
    private Status current(PoolSession s){return repository.status(s.metadata().info().username(),s.metadata().info().database());}
    public Status status(PoolSession s){return bound(s,()->current(s));}
    public Page page(PoolSession s,Query q){return bound(s,()->repository.page(q));}
    public Map<String,Object> detail(PoolSession s,Selection item){return bound(s,()->repository.detail(item));}
    public Preview preview(PoolSession s,Operation op,Settings settings){return bound(s,()->{
        var status=current(s);return new Preview(UUID.randomUUID().toString(),op,settings,com.dbcompanion.model.AuditHistory.fingerprint(status),Instant.now().plusSeconds(600),AuditHistorySql.plan(status,op,settings));
    });}
    public Status apply(PoolSession s,Preview p){return bound(s,()->{
        var status=current(s);if(!Instant.now().isBefore(p.expires())||!p.fingerprint().equals(com.dbcompanion.model.AuditHistory.fingerprint(status)))throw new IllegalArgumentException("Preview expired or changed");
        repository.apply(AuditHistorySql.plan(status,p.operation(),p.settings()));return current(s);
    });}
}
