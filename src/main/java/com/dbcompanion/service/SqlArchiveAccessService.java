package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.repository.SqlArchiveAccessRepository;
import com.dbcompanion.repository.SqlArchiveAccessRepository.Access;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class SqlArchiveAccessService {
    private final SessionDataSource source;
    private final SqlArchiveAccessRepository repository;
    public SqlArchiveAccessService(SessionDataSource source,SqlArchiveAccessRepository repository){this.source=source;this.repository=repository;}
    public record Preview(String token,String database,Access before,List<String> statements,Instant expires){
        public Preview { statements=List.copyOf(statements); }
    }
    private <T>T bound(PoolSession session,Supplier<T> work){
        synchronized(session){
            if(!"ADMIN".equals(session.metadata().info().username()))throw new SecurityException("ADMIN login required");
            source.bind(session.pool(),"ADMIN");
            try{return work.get();}finally{source.clear();}
        }
    }
    public List<String> users(PoolSession session){return bound(session,repository::users);}
    public Access inspect(PoolSession session,String username){return bound(session,()->repository.inspect(username));}
    public Preview preview(PoolSession session,String username){return bound(session,()->{
        var before=repository.inspect(username);var sql=SqlArchiveAccessRepository.plan(before);
        if(sql.isEmpty())throw new IllegalArgumentException("Already granted");
        return new Preview(UUID.randomUUID().toString(),session.metadata().info().database(),before,sql,Instant.now().plusSeconds(300));
    });}
    public Access apply(PoolSession session,Preview preview){return bound(session,()->{
        if(preview==null||preview.expires().isBefore(Instant.now())||!preview.database().equals(session.metadata().info().database()))
            throw new IllegalArgumentException("Expired or different connection");
        // Only the server's session-bound snapshot determines target and missing grants.
        return repository.apply(preview.before());
    });}
}
