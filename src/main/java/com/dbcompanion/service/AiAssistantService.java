package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.AiAssistant.*;
import com.dbcompanion.model.FunctionCatalog;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiAssistantService {
    private final SessionDataSource source;
    private final AiAssistantRepository repository;
    private final ProfileHistoryRepository profiles;
    private final FunctionCatalogService functions;
    private final TransactionTemplate read,generate,write;
    public AiAssistantService(SessionDataSource source,AiAssistantRepository repository,ProfileHistoryRepository profiles,FunctionCatalogService functions){
        this.source=source;this.repository=repository;this.profiles=profiles;this.functions=functions;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(10);
        generate=new TransactionTemplate(manager);generate.setReadOnly(true);generate.setTimeout(90);
        write=new TransactionTemplate(manager);write.setTimeout(10);
    }
    private <T>T query(PoolSession session,boolean generating,Supplier<T> work){
        source.bind(session.pool(),session.metadata().info().username(),session.metadata().assistant());
        try{return (generating?generate:read).execute(status->work.get());}finally{source.clear();}
    }
    public Options options(PoolSession session,boolean refresh){
        synchronized(session){var metadata=session.metadata();var state=metadata.assistant();
            var choices=state.profiles(refresh,()->query(session,false,repository::profiles));
            var selected=state.selected();
            TokenSettings tokens=selected==null?null:query(session,false,()->new TokenSettings(selected.name(),repository.profile(selected).version(),repository.maxTokens(selected.name()),state.maxTokens()));
            return new Options(metadata.info().username(),selected,choices,tokens);
        }
    }
    public synchronized void tokens(PoolSession session,String name,String version,Integer value,boolean persistent,boolean consent){
        AiAssistant.validateTokens(value);
        if(persistent&&(value==null||!consent))throw new IllegalArgumentException("Explicit profile write confirmation required");
        synchronized(session){
            var state=session.metadata().assistant();var selected=state.selected();
            if(selected==null||!selected.name().equals(name))throw AiAssistant.stale();
            state.select(selected); // Busy guard and invalidate outstanding assistant consent.
            source.bind(session.pool(),session.metadata().info().username());
            try{
                read.execute(status->{
                    if(!repository.profile(selected).version().equals(version))throw AiAssistant.stale();
                    return null;
                });
                if(persistent)persistTokens(selected,version,value);
                state.maxTokens(persistent?null:value);
            }finally{source.clear();}
        }
    }
    private record TokenBefore(String owner,String requestId,java.util.Map<String,Object> snapshot) {}
    private void persistTokens(Selection selected,String version,int value){
        // Preserve the existing profile editor's before/after archive requirement.
        var before=write.execute(status->{
            profiles.requireArchive(selected.owner());
            if(!repository.profile(selected).version().equals(version))throw AiAssistant.stale();
            var snapshot=profiles.currentSnapshot(selected.owner(),selected.name(),true);
            String id=String.valueOf(((java.util.Map<?,?>)snapshot.get("profile")).get("PROFILE_ID"));
            String request=profiles.beforeEdit(selected.owner(),selected.name(),id,"max_tokens",selected.owner(),snapshot);
            return new TokenBefore(profiles.packageOwner(selected.owner()),request,snapshot);
        }); // Commit the recovery snapshot before invoking a potentially committing DB API.
        RuntimeException failure=null;
        try{
            write.executeWithoutResult(status->{
                if(!repository.profile(selected).version().equals(version))throw AiAssistant.stale();
                repository.saveMaxTokens(before.owner(),selected.name(),value);
            });
        }catch(RuntimeException ex){failure=ex;}
        try{
            var after=read.execute(status->profiles.currentSnapshot(selected.owner(),selected.name(),true));
            var target=new com.dbcompanion.model.ProfileEdit.Target(selected.owner(),selected.name(),"max_tokens");
            boolean matches=ProfileEditPolicy.readbackMatches(target,before.snapshot(),after,Integer.toString(value),new tools.jackson.databind.json.JsonMapper());
            String outcome=matches&&failure==null?"VERIFIED":"UNCERTAIN";
            write.executeWithoutResult(status->profiles.afterEdit(selected.owner(),selected.name(),before.requestId(),outcome,after));
            if(!matches||failure!=null)throw tokenSaveFailure();
        }catch(RuntimeException ex){throw tokenSaveFailure();}
    }
    private Failure tokenSaveFailure(){return new Failure(503,"assistant.tokens.verifyFailed","저장 결과를 확인하지 못했습니다. 새로고침 후 DB 값과 변경 이력을 확인하세요. 자동 재시도하지 않았습니다.");}
    public Selection select(PoolSession session,String name){
        synchronized(session){
            var metadata=session.metadata();var state=metadata.assistant();
            if(name==null||name.isEmpty()){state.select(null);return null;}
            if(name.length()>128||name.indexOf('\0')>=0)throw AiAssistant.stale();
            var selection=new Selection(metadata.info().username(),name);
            query(session,false,()->repository.profile(selection));state.select(selection);return selection;
        }
    }
    public Preview preview(PoolSession session,String schema,String reference,Locale locale){
        if(schema==null||reference==null||schema.isBlank()||reference.isBlank())throw new IllegalArgumentException("Missing source reference");
        synchronized(session){
            var state=session.metadata().assistant();var selected=state.selected();
            if(selected==null)throw new Failure(409,"assistant.chooseFirst","AI 도우미 설정에서 프로필을 먼저 선택해 주세요.");
            var details=functions.detail(session,schema,reference);
            if(details.size()!=1)throw new Failure(422,"assistant.ambiguous","함수 소스를 하나로 확인할 수 없습니다.");
            var definition=details.getFirst().definition();String text=AiAssistant.source(definition);
            var profile=query(session,false,()->repository.profile(selected));
            return state.prepare(schema,profile,FunctionCatalog.reference(definition.owner(),definition.object(),definition.member()),
                    text,definition.member()!=null,com.dbcompanion.common.i18n.UiMessages.supported(locale).getLanguage(),Instant.now());
        }
    }
    public Result explain(PoolSession session,String token,boolean consent){
        final Draft draft;final State state;
        synchronized(session){state=session.metadata().assistant();draft=state.consume(token,consent,session.metadata().selectedSchema(),Instant.now());}
        try{
            return query(session,true,()->{
                var before=draft.preview().profile();var now=repository.profile(before.selection());
                if(!before.equals(now))throw AiAssistant.stale();
                String owner=profiles.packageOwner(before.selection().owner());
                String result=repository.explain(owner,before.selection().name(),AiAssistant.prompt(draft));
                return new Result(draft.preview().reference(),before.selection().owner()+"."+before.selection().name(),result);
            });
        }finally{state.finish();}
    }
}
