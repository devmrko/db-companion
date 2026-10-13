package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.GlossaryFunction.*;
import com.dbcompanion.repository.GlossaryFunctionRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class GlossaryFunctionService {
    private final SessionDataSource source;
    private final GlossaryFunctionRepository repository;
    public GlossaryFunctionService(SessionDataSource source,GlossaryFunctionRepository repository){this.source=source;this.repository=repository;}
    private <T>T bound(PoolSession session,Supplier<T> work){
        synchronized(session){
            source.bind(session.pool(),session.metadata().info().username());
            try{return work.get();}finally{source.clear();}
        }
    }
    private String owner(PoolSession session){return session.metadata().info().username();}
    public Status status(PoolSession session){return bound(session,()->repository.status(owner(session)));}
    public Preview preview(PoolSession session){
        return bound(session,()->{
            var status=repository.status(owner(session));
            if(!status.installable())throw new IllegalArgumentException("Check dictionary, Oracle Text, CREATE PROCEDURE and object-name availability");
            return new Preview(UUID.randomUUID().toString(),status.owner(),status.sql(),Instant.now().plusSeconds(300));
        });
    }
    public Status install(PoolSession session,Preview preview){
        return bound(session,()->{
            if(!preview.owner().equals(owner(session))||!Instant.now().isBefore(preview.expires())
                    ||!preview.sql().equals(com.dbcompanion.common.db.GlossaryFunctionSql.create(owner(session))))
                throw new IllegalArgumentException("The creation preview expired or changed; preview again");
            return repository.install(owner(session));
        });
    }
    public Object search(PoolSession session,Question question){return bound(session,()->repository.search(owner(session),question.question()));}
}
