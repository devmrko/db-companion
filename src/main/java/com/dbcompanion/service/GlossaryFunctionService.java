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
    private final SemanticChangeService history;
    public GlossaryFunctionService(SessionDataSource source,GlossaryFunctionRepository repository,SemanticChangeService history){this.source=source;this.repository=repository;this.history=history;}
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
            return new Preview(UUID.randomUUID().toString(),status.owner(),status.sql(),Instant.now().plusSeconds(300),status.fingerprint());
        });
    }
    public Status install(PoolSession session,Preview preview){
        return bound(session,()->{
            var current=repository.status(owner(session));
            if(!preview.owner().equals(owner(session))||!Instant.now().isBefore(preview.expires())
                    ||!preview.sql().equals(current.sql())||!preview.fingerprint().equals(current.fingerprint()))
                throw new IllegalArgumentException("The creation preview expired or changed; preview again");
            String owner=owner(session);
            var result=history.change(owner,"CALLBACK","DBC_GLOSSARY_CONTEXT","INSTALL_OR_UPGRADE",repository.versions(owner),
                java.util.Map.of("plannedComponents",current.codes().stream().filter(c->!c.action().equals("KEEP"))
                    .map(c->java.util.Map.of("name",c.name(),"type",c.type(),"action",c.action())).toList()),
                ()->{
                    if(!repository.status(owner).fingerprint().equals(preview.fingerprint()))throw new IllegalArgumentException("Stored code changed; preview again");
                    repository.install(owner);
                },()->repository.versions(owner),after->"READY".equals(after.get("moduleState")));
            if(!result.verified())throw com.dbcompanion.model.SemanticChange.error(503,"uncertain");
            return repository.status(owner);
        });
    }
    public Object search(PoolSession session,Question question){return bound(session,()->repository.search(owner(session),question.question()));}
}
