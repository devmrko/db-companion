package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OntologyQueryService {
    private final SessionDataSource source;private final OntologyRepository repository;private final AiAssistantRepository ai;private final ProfileHistoryRepository profiles;
    private final OntologyQueryRepository queries;private final PropertyGraphRepository graphs;private final JsonMapper json;private final TransactionTemplate read,generate,execute;
    public record Preview(String mode,AiAssistant.Preview request){}
    public OntologyQueryService(SessionDataSource source,OntologyRepository repository,AiAssistantRepository ai,ProfileHistoryRepository profiles,OntologyQueryRepository queries,PropertyGraphRepository graphs,JsonMapper json){
        this.source=source;this.repository=repository;this.ai=ai;this.profiles=profiles;this.queries=queries;this.graphs=graphs;this.json=json;
        var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(60);generate=new TransactionTemplate(manager);generate.setReadOnly(true);generate.setTimeout(120);
        var strict=new DataSourceTransactionManager(source);strict.setEnforceReadOnly(true);execute=new TransactionTemplate(strict);execute.setReadOnly(true);execute.setTimeout(60);
    }
    private void scope(PoolSession s,String schema){Ontology.name(schema);if(!schema.equals(s.metadata().selectedSchema()))throw new Failure(409,"query.stale");}
    private String login(PoolSession s){return s.metadata().info().username();}
    private <T>T query(PoolSession s,String schema,TransactionTemplate tx,Supplier<T> fn){source.bind(s.pool(),schema);try{return tx.execute(status->fn.get());}finally{source.clear();}}
    private OntologyInquiry.Dataset dataset(PoolSession s,String schema,boolean refresh){return s.metadata().inquiry().dataset(schema,refresh,()->query(s,login(s),read,()->{
        var entries=repository.relationshipEntries(schema,login(s));var now=Instant.now().toString();return new OntologyInquiry.Dataset(schema,entries,OntologyRelations.analyze(s.metadata().info().database(),schema,entries,now),now);
    }));}
    public OntologyInquiry.Options options(PoolSession s,String schema,boolean refresh){synchronized(s){scope(s,schema);var d=dataset(s,schema,refresh);return new OntologyInquiry.Options(schema,d.entries().stream().map(e->new OntologyInquiry.Choice(e.document().source().table(),e.document().meaning().concept(),e.state(),e.revision())).toList(),d.checkedAt());}}
    public OntologyInquiry.Search search(PoolSession s,String schema,String question,String anchor){synchronized(s){scope(s,schema);var state=s.metadata().inquiry();state.invalidate();var result=OntologyInquiry.search(dataset(s,schema,false),question,anchor);state.remember(result);return result;}}
    private void verify(PoolSession s,String schema,List<Entry> entries){
        repository.require(schema,login(s));for(var e:entries){var current=repository.entry(schema,e.document().source().table(),0);if(current==null||!e.state().equals(current.state())||!PropertyGraph.sameDefinitions(List.of(e),List.of(current)))throw new Failure(409,"query.stale");}
    }
    public Preview preview(PoolSession s,String id,String mode,String route,Locale locale){synchronized(s){
        if(!Set.of("ANSWER","SQL").contains(Objects.toString(mode,"")))throw new Failure(400,"query.invalid");String schema=s.metadata().selectedSchema();var state=s.metadata().inquiry();state.invalidate();
        var selected=OntologyInquiry.chooseRoute(state.search(id,schema),route);var entries=OntologyInquiry.selected(selected,state.data());var assistant=s.metadata().assistant();var selection=assistant.selected();if(selection==null)throw new Failure(409,"chooseProfile");
        return query(s,login(s),read,()->{
            verify(s,schema,entries);var profile=ai.profile(selection);String payload=OntologyInquiry.payload(selected,entries,json);
            if(mode.equals("SQL")){
                queries.profileScope(selection.name(),schema,entries);queries.localTables(entries);graphs.verifyMetadata(entries);
                var providerMetadata=entries.stream().map(e->Map.of("table",e.document().source().table(),"columns",e.document().source().columns().stream().map(c->Map.of("name",c.name(),"type",c.dataType())).toList())).toList();
                payload=json.writeValueAsString(Map.of("context",json.readTree(payload),"allowedColumns",ReviewedSql.scope(selected,entries).tables(),"providerMetadata",providerMetadata,"attributes",attributes(schema,entries)));
            }
            if(payload.length()>AiAssistant.MAX_SOURCE)throw new Failure(413,"query.narrow");
            var request=assistant.prepare(schema,profile,"ONTOLOGY_QUERY",payload,false,locale.getLanguage(),Instant.now(),"inquiry");state.prepare(new OntologyInquiry.Prepared(request.token(),mode,selected,entries));return new Preview(mode,request);
        });
    }}
    private Map<String,Object> attributes(String schema,List<Entry> entries){return Map.of("conversation",false,"comments",false,"annotations",false,"enforce_object_list",true,"object_list",entries.stream().map(e->Map.of("owner",schema,"name",e.document().source().table())).toList());}
    public OntologyInquiry.Outcome generate(PoolSession s,String token,boolean consent){
        final AiAssistant.Draft draft;final OntologyInquiry.Prepared prepared;var assistant=s.metadata().assistant();var state=s.metadata().inquiry();
        synchronized(s){draft=assistant.consume(token,consent,s.metadata().selectedSchema(),Instant.now(),"inquiry");try{prepared=state.consume(token,s.metadata().selectedSchema());}catch(RuntimeException ex){assistant.finish();throw ex;}}
        try{return query(s,login(s),generate,()->{
            var search=prepared.search();verify(s,search.schema(),prepared.entries());var profile=draft.preview().profile();if(!profile.equals(ai.profile(profile.selection())))throw new Failure(409,"query.stale");
            String owner=profiles.packageOwner(profile.selection().owner()),profileName=profile.selection().owner()+"."+profile.selection().name();
            if(prepared.mode().equals("ANSWER")){
                String output=ai.explain(owner,profile.selection().name(),OntologyInquiry.prompt(draft));var answer=OntologyInquiry.parse(output,search,profileName,json);
                var outcome=new OntologyInquiry.Outcome("ANSWER",answer,null);
                synchronized(s){scope(s,search.schema());state.search(search.id(),search.schema());state.current(token);state.outcome(search.routes().getFirst().id(),outcome);}return outcome;
            }
            queries.profileScope(profile.selection().name(),search.schema(),prepared.entries());queries.localTables(prepared.entries());graphs.verifyMetadata(prepared.entries());
            String sql=queries.showsql(owner,profile.selection().name(),OntologyInquiry.sqlPrompt(draft),json.writeValueAsString(attributes(search.schema(),prepared.entries())));boolean allowed=true;String reason="";
            try{sql=ReviewedSql.check(sql,ReviewedSql.scope(search,prepared.entries())).sql();}catch(Failure ex){allowed=false;reason=ex.getMessage();}
            var value=new OntologyInquiry.SqlDraft(UUID.randomUUID().toString(),search.id(),sql,hash(sql),allowed,reason,profileName,Instant.now().toString(),Instant.now().plusSeconds(600));
            var outcome=new OntologyInquiry.Outcome("SQL",null,value);
            synchronized(s){scope(s,search.schema());state.search(search.id(),search.schema());state.current(token);if(allowed)state.execution(new OntologyInquiry.Execution(value,search,prepared.entries(),profile));state.outcome(search.routes().getFirst().id(),outcome);}
            return outcome;
        });}finally{assistant.finish();}
    }
    public OntologyInquiry.Rows execute(PoolSession s,String token,boolean confirmed){synchronized(s){
        var value=s.metadata().inquiry().execute(token,s.metadata().selectedSchema(),confirmed,Instant.now());var search=value.search();scope(s,search.schema());
        // Profile lookup uses login schema; the reviewed query itself uses the selected schema.
        query(s,login(s),read,()->{if(!Objects.equals(s.metadata().assistant().selected(),value.profile().selection())||!value.profile().equals(ai.profile(value.profile().selection())))throw new Failure(409,"query.stale");queries.profileScope(value.profile().selection().name(),search.schema(),value.entries());return true;});
        return query(s,search.schema(),execute,()->{
            verify(s,search.schema(),value.entries());queries.localTables(value.entries());graphs.verifyMetadata(value.entries());var checked=ReviewedSql.check(value.draft().sql(),ReviewedSql.scope(search,value.entries()));
            if(!checked.sql().equals(value.draft().sql())||!hash(checked.sql()).equals(value.draft().hash()))throw new Failure(409,"query.stale");return queries.execute(value,login(s));
        });
    }}
    public void cancel(PoolSession s,String token){synchronized(s){s.metadata().inquiry().cancel(token);s.metadata().assistant().discard(token);}}
    public void invalidate(PoolSession s){synchronized(s){s.metadata().inquiry().invalidate();}}
    public static String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}
