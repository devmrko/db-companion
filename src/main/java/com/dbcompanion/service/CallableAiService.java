package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.CallableAi.*;
import com.dbcompanion.repository.CallableAiRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class CallableAiService {
    private final SessionDataSource source;private final CallableAiRepository repository;
    public CallableAiService(SessionDataSource source,CallableAiRepository repository){this.source=source;this.repository=repository;}
    private String owner(PoolSession session){return session.metadata().info().username();}
    private <T>T bound(PoolSession session,Supplier<T> action){synchronized(session){source.bind(session.pool(),owner(session));try{return action.get();}finally{source.clear();}}}
    public Status status(PoolSession session){return bound(session,()->repository.status(owner(session)));}
    private void admin(PoolSession session){if(!owner(session).equals("ADMIN"))throw new IllegalArgumentException("ADMIN login required");}
    public List<String> users(PoolSession session){admin(session);return bound(session,repository::users);}
    public Access access(PoolSession session,String username){admin(session);return bound(session,()->repository.access(username));}
    public Grant grantPreview(PoolSession session,String username){admin(session);return bound(session,()->{
        if(repository.access(username).granted())throw new IllegalArgumentException("Privilege already granted");
        return new Grant(UUID.randomUUID().toString(),owner(session),username,CallableAiRepository.grantSql(username),Instant.now().plusSeconds(300));
    });}
    public Access grant(PoolSession session,Grant preview){admin(session);return bound(session,()->{
        if(!preview.owner().equals(owner(session))||!Instant.now().isBefore(preview.expires()))throw new IllegalArgumentException("Grant preview expired");
        return repository.grant(preview.username());
    });}
    public String script(PoolSession session){return CallableAiSql.script(owner(session));}
    public Install preview(PoolSession session){return bound(session,()->{
        if(!List.of("MISSING","INCOMPLETE").contains(repository.state(owner(session))))throw new IllegalArgumentException("Existing package is not replaced");
        return new Install(UUID.randomUUID().toString(),owner(session),repository.fingerprint(),script(session),Instant.now().plusSeconds(300));
    });}
    public Status install(PoolSession session,Install preview){return bound(session,()->{
        check(session,preview.owner(),preview.fingerprint(),preview.expires());repository.install(owner(session));return repository.status(owner(session));
    });}
    public Run prepare(PoolSession session,Request request){return bound(session,()->{
        var status=repository.status(owner(session));
        if(!status.state().equals("READY")||!status.profiles().contains(request.profile()))throw new IllegalArgumentException("Choose an enabled profile and a ready package");
        return new Run(UUID.randomUUID().toString(),owner(session),request,repository.fingerprint(),Instant.now().plusSeconds(300));
    });}
    public JsonNode run(PoolSession session,Run preview){return bound(session,()->{check(session,preview.owner(),preview.fingerprint(),preview.expires());return repository.run(owner(session),preview.request());});}
    private void check(PoolSession session,String owner,String fingerprint,Instant expires){
        if(!owner(session).equals(owner)||!Instant.now().isBefore(expires)||!repository.fingerprint().equals(fingerprint))throw new IllegalArgumentException("Preview expired or package changed");
    }
}
