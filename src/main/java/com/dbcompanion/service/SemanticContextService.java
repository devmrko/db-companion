package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.SemanticContext;
import com.dbcompanion.model.SemanticContext.*;
import com.dbcompanion.repository.SemanticContextRepository;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class SemanticContextService {
    private final SessionDataSource source;
    private final SemanticContextRepository repository;
    public SemanticContextService(SessionDataSource source, SemanticContextRepository repository) {
        this.source = source; this.repository = repository;
    }
    public record Status(String version, boolean supported, String reason, String baseline, boolean editable, List<Source> sources) {}
    public record Access(String username,boolean granted) {}
    public record Grant(String token,String username,String packageOwner,String sql,java.time.Instant expires) {}
    private <T> T admin(PoolSession session,Supplier<T> action) {
        if (!session.metadata().info().username().equals("ADMIN")) throw SemanticContext.error(403,"accessAdmin");
        synchronized(session) { source.bind(session.pool(),"ADMIN");try{return action.get();}finally{source.clear();} }
    }
    public List<String> accessUsers(PoolSession session) {return admin(session,repository::accessUsers);}
    public Access access(PoolSession session,String username) {return admin(session,()->new Access(username,repository.accessGranted(repository.require().owner(),username)));}
    public Grant accessPreview(PoolSession session,String username) {return admin(session,()->{
        String owner=repository.require().owner();
        if(repository.accessGranted(owner,username))throw SemanticContext.error(409,"accessAlready");
        return new Grant(java.util.UUID.randomUUID().toString(),username,owner,SemanticContextRepository.accessSql(owner,username),java.time.Instant.now().plusSeconds(300));
    });}
    public Access grantAccess(PoolSession session,Grant preview) {return admin(session,()->{
        String owner=repository.require().owner();
        if(preview==null||!java.time.Instant.now().isBefore(preview.expires())||!owner.equals(preview.packageOwner())
            ||!SemanticContextRepository.accessSql(owner,preview.username()).equals(preview.sql()))throw SemanticContext.error(409,"accessExpired");
        repository.grantAccess(owner,preview.username());
        return new Access(preview.username(),repository.accessGranted(owner,preview.username()));
    });}
    private <T> T bound(PoolSession session, String schema, Supplier<T> action) {
        synchronized (session) {
            if (!session.metadata().selectedSchema().equals(schema)) throw SemanticContext.error(409, "schemaChanged");
            source.bind(session.pool(), schema);
            try { return action.get(); } finally { source.clear(); }
        }
    }
    public Status status(PoolSession session, String schema) {
        return bound(session, schema, () -> {
            var capability = repository.capability();
            boolean editable = schema.equals(session.metadata().info().username());
            // USER_* views always refer to the login owner, never a browsed foreign schema.
            var sources = capability.supported() && editable ? repository.sources(capability.owner()) : List.<Source>of();
            return new Status(capability.version(), capability.supported(), capability.reason(), SemanticContext.VERIFIED_VERSION, editable, sources);
        });
    }
    public synchronized Result create(PoolSession session, Create input) {
        var expected = SemanticContext.validate(input);
        return bound(session, input.schema(), () -> {
            if (!input.schema().equals(session.metadata().info().username())) throw SemanticContext.error(403, "ownerOnly");
            var capability = repository.require();
            if (repository.sources(capability.owner()).stream().anyMatch(s -> s.name().equals(input.name())))
                throw SemanticContext.error(409, "exists");
            // The native API can commit. After an attempted call never encourage a blind retry.
            try {
                repository.create(capability.owner(), input);
                if (repository.matches(capability.owner(), input, expected))
                    return new Result(true, UiMessages.text("semanticContext.created", "Semantic Source created and verified."));
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Semantic source creation/readback could not be verified");
            }
            return new Result(false, UiMessages.text("semanticContext.uncertain", "Check the current DB state before retrying."));
        });
    }
}
