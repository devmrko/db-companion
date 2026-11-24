package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.SemanticContext;
import com.dbcompanion.model.SemanticContext.*;
import com.dbcompanion.repository.SemanticContextRepository;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class SemanticContextService {
    private final SessionDataSource source;
    private final SemanticContextRepository repository;
    private final SemanticChangeService history;
    private final tools.jackson.databind.json.JsonMapper json=new tools.jackson.databind.json.JsonMapper();
    public SemanticContextService(SessionDataSource source, SemanticContextRepository repository,SemanticChangeService history) {
        this.source = source; this.repository = repository;this.history=history;
    }
    public record Status(String version, boolean supported, String reason, String baseline, boolean editable, List<Source> sources) {}
    public record Access(String username,boolean granted) {}
    public record Grant(String token,String username,String packageOwner,String sql,Instant expires) {}
    private <T> T admin(PoolSession session,Supplier<T> action) {
        if (!session.metadata().info().username().equals("ADMIN")) throw SemanticContext.error(403,"accessAdmin");
        synchronized(session) { source.bind(session.pool(),"ADMIN");try{return action.get();}finally{source.clear();} }
    }
    public List<String> accessUsers(PoolSession session) {return admin(session,repository::accessUsers);}
    public Access access(PoolSession session,String username) {return admin(session,()->new Access(username,repository.accessGranted(repository.require().owner(),username)));}
    public Grant accessPreview(PoolSession session,String username) {return admin(session,()->{
        String owner=repository.require().owner();
        if(repository.accessGranted(owner,username))throw SemanticContext.error(409,"accessAlready");
        return new Grant(java.util.UUID.randomUUID().toString(),username,owner,SemanticContextRepository.accessSql(owner,username),Instant.now().plusSeconds(300));
    });}
    public Access grantAccess(PoolSession session,Grant preview) {return admin(session,()->{
        String owner=repository.require().owner();
        if(preview==null||!Instant.now().isBefore(preview.expires())||!owner.equals(preview.packageOwner())
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
            var before=repository.snapshot(capability.owner(),input.name());
            var result=history.change(input.schema(),"SOURCE",input.name(),"CREATE",before,input,()->{
                if(!before.equals(repository.snapshot(capability.owner(),input.name())))throw SemanticContext.error(409,"exists");
                repository.create(capability.owner(),input);
            },()->repository.snapshot(capability.owner(),input.name()),after->repository.matches(capability.owner(),input,expected));
            return new Result(result.verified(),result.message());
        });
    }
    public record EditForm(Map<String,Object> current,String attributes,String version) {}
    public record EditPlan(String token,String owner,SemanticContext.Edit input,String version,Instant expires) {}
    private void own(PoolSession session,String schema){if(!schema.equals(session.metadata().info().username()))throw SemanticContext.error(403,"ownerOnly");}
    private Map<String,Object> current(String owner,String name){
        com.dbcompanion.model.AiCreation.name(name);var value=repository.snapshot(owner,name);
        if(!Boolean.TRUE.equals(value.get("exists")))throw SemanticContext.error(404,"sourceUnavailable");return value;
    }
    private tools.jackson.databind.JsonNode attributes(Map<String,Object> current){
        var output=json.createObjectNode();
        var raw=(Map<?,?>)current.get("attributes");
        raw.forEach((key,value)->{
            String text=java.util.Objects.toString(value,"");
            if(java.util.Set.of("global_callback_func","object_callback_func").contains(key.toString()))text=text.replaceAll("\"([A-Z][A-Z0-9_$#]*)\"","$1");
            if(key.equals("params"))output.set(key.toString(),json.readTree(text));else output.put(key.toString(),text);
        });return output;
    }
    public EditForm editForm(PoolSession session,String schema,String name){return bound(session,schema,()->{
        own(session,schema);var cap=repository.require();var state=current(cap.owner(),name);
        return new EditForm(state,json.writeValueAsString(attributes(state)),com.dbcompanion.model.AiCreation.fingerprint(state,json));
    });}
    private void validateEdit(Map<String,Object> current,SemanticContext.Edit input){
        if(input.operation()==null||!java.util.Set.of("ENABLE","DISABLE","ATTRIBUTES").contains(input.operation()))throw SemanticContext.error(400,"invalid");
        if(!input.operation().equals("ATTRIBUTES")){
            String target=input.operation().equals("ENABLE")?"enabled":"disabled";
            if(target.equalsIgnoreCase(current.get("status").toString()))throw SemanticContext.error(409,"unchanged");return;
        }
        if(!"custom".equalsIgnoreCase(current.get("type").toString()))throw SemanticContext.error(409,"unsupportedEdit");
        var parsed=SemanticContext.validate(new Create(input.schema(),input.name(),"custom",input.attributes(),current.get("description").toString(),true));
        var previous=attributes(current);
        if(!parsed.path("scope").asString().equals("global")||!previous.path("scope").asString().equals("global")
            ||parsed.properties().stream().anyMatch(e->!java.util.Set.of("scope","global_callback_func","params").contains(e.getKey()))
            ||previous.properties().stream().anyMatch(e->!parsed.has(e.getKey())))throw SemanticContext.error(400,"unsupportedEdit");
        if(previous.equals(parsed))throw SemanticContext.error(409,"unchanged");
    }
    public EditPlan editPreview(PoolSession session,SemanticContext.Edit input){
        if(input==null)throw SemanticContext.error(400,"invalid");
        return bound(session,input.schema(),()->{
            own(session,input.schema());var cap=repository.require();var state=current(cap.owner(),input.name());validateEdit(state,input);
            String version=com.dbcompanion.model.AiCreation.fingerprint(state,json);
            if(!version.equals(input.version()))throw SemanticContext.error(409,"stale");
            return new EditPlan(java.util.UUID.randomUUID().toString(),input.schema(),input,version,Instant.now().plusSeconds(300));
        });
    }
    public synchronized com.dbcompanion.model.SemanticChange.Result editApply(PoolSession session,EditPlan plan){
        if(plan==null||!plan.expires().isAfter(Instant.now()))throw SemanticContext.error(409,"stale");
        return bound(session,plan.owner(),()->{
            own(session,plan.owner());var cap=repository.require();var input=plan.input();var before=current(cap.owner(),input.name());
            if(!plan.version().equals(com.dbcompanion.model.AiCreation.fingerprint(before,json)))throw SemanticContext.error(409,"stale");
            validateEdit(before,input);
            return history.change(plan.owner(),"SOURCE",input.name(),input.operation(),before,input,()->{
                if(!plan.version().equals(com.dbcompanion.model.AiCreation.fingerprint(current(cap.owner(),input.name()),json)))throw SemanticContext.error(409,"stale");
                repository.update(cap.owner(),input.name(),input.operation(),input.attributes());
            },()->repository.snapshot(cap.owner(),input.name()),after->{
                if(!Boolean.TRUE.equals(after.get("exists")))return false;
                if(input.operation().equals("ATTRIBUTES"))return attributes(after).equals(json.readTree(input.attributes()));
                return (input.operation().equals("ENABLE")?"enabled":"disabled").equalsIgnoreCase(after.get("status").toString());
            });
        });
    }
}
