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
    private final TransactionTemplate read,generate;
    public AiAssistantService(SessionDataSource source,AiAssistantRepository repository,ProfileHistoryRepository profiles,FunctionCatalogService functions){
        this.source=source;this.repository=repository;this.profiles=profiles;this.functions=functions;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(10);
        generate=new TransactionTemplate(manager);generate.setReadOnly(true);generate.setTimeout(90);
    }
    private <T>T query(PoolSession session,boolean generating,Supplier<T> work){
        source.bind(session.pool(),session.metadata().info().username());
        try{return (generating?generate:read).execute(status->work.get());}finally{source.clear();}
    }
    public Options options(PoolSession session,boolean refresh){
        synchronized(session){var metadata=session.metadata();var state=metadata.assistant();
            return new Options(metadata.info().username(),state.selected(),state.profiles(refresh,()->query(session,false,repository::profiles)));
        }
    }
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
