package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.SqlCacheArchive.*;
import com.dbcompanion.repository.SqlCacheArchiveRepository;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class SqlCacheArchiveService {
    private final SessionDataSource source;
    private final SqlCacheArchiveRepository repository;
    public SqlCacheArchiveService(SessionDataSource source,SqlCacheArchiveRepository repository){this.source=source;this.repository=repository;}
    private <T>T bound(PoolSession session,Supplier<T> work){
        synchronized(session){
            source.bind(session.pool(),session.metadata().info().username());
            try{return work.get();}finally{source.clear();}
        }
    }
    public Status status(PoolSession session){return bound(session,()->repository.status(session.metadata().info().username()));}
    public Preview preview(PoolSession session,Operation op,int seconds){
        return bound(session,()->{var s=repository.status(session.metadata().info().username());return Preview.of(op,seconds,s,SqlCacheArchiveRepository.plan(op,seconds,s));});
    }
    public Status apply(PoolSession session,Preview p){
        return bound(session,()->{
            var s=repository.status(session.metadata().info().username());
            if(p==null || p.expires().isBefore(Instant.now()) || !p.owner().equals(s.owner())
                    || !p.fingerprint().equals(com.dbcompanion.model.SqlCacheArchive.fingerprint(s))) throw new IllegalArgumentException("Preview expired or settings changed");
            // Rebuild server-side; never execute browser-supplied SQL, schema, job or table names.
            repository.apply(SqlCacheArchiveRepository.plan(p.operation(),p.seconds(),s));
            return repository.status(s.owner());
        });
    }
    public Page page(PoolSession session,Query q){return bound(session,()->repository.page(q));}
    public Map<String,Object> detail(PoolSession session,String key){return bound(session,()->repository.detail(key));}
}
