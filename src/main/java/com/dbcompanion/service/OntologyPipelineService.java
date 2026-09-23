package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OntologyPipelineService {
    private final SessionDataSource source;private final OntologyRepository repository;private final AiAssistantRepository ai;private final ProfileHistoryRepository profiles;private final PropertyGraphRepository graphs;private final JsonMapper json;
    private final TransactionTemplate read,generate,ddl;
    public OntologyPipelineService(SessionDataSource source,OntologyRepository repository,AiAssistantRepository ai,ProfileHistoryRepository profiles,PropertyGraphRepository graphs,JsonMapper json){
        this.source=source;this.repository=repository;this.ai=ai;this.profiles=profiles;this.graphs=graphs;this.json=json;
        var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(60);
        generate=new TransactionTemplate(manager);generate.setReadOnly(true);generate.setTimeout(90);ddl=new TransactionTemplate(manager);ddl.setTimeout(60);
    }
    private String login(PoolSession s){return s.metadata().info().username();}
    private void scope(PoolSession s,String schema){Ontology.name(schema);if(!schema.equals(s.metadata().selectedSchema()))throw new Failure(409,"stale");}
    private <T>T query(PoolSession s,TransactionTemplate tx,Supplier<T> action){source.bind(s.pool(),login(s));try{return tx.execute(status->action.get());}finally{source.clear();}}
    private List<Entry> entries(PoolSession s,String schema){return repository.relationshipEntries(schema,login(s));}
    private OntologyRelations.Analysis analysis(PoolSession s,String schema,List<Entry> entries){return OntologyRelations.analyze(s.metadata().info().database(),schema,entries,Instant.now().toString());}
    private List<Entry> selectedEntries(PoolSession s,String schema,List<String> tables){
        var rows=repository.relationshipEntries(schema,login(s),tables);
        if(rows.stream().anyMatch(e->!s.metadata().info().database().equals(e.document().source().database())))throw new Failure(409,"mismatch");return rows;
    }
    private List<String> selectedTables(PoolSession s){return s.metadata().ontology().pipeline().references().stream().map(OntologyAnalysis.Reference::table).toList();}
    public OntologyDiscovery.Preview preview(PoolSession s,String schema,List<String> tables){synchronized(s){scope(s,schema);return query(s,read,()->{
        var entries=selectedEntries(s,schema,tables);var selection=s.metadata().assistant().selected();if(selection==null)throw new Failure(409,"chooseProfile");
        var profile=ai.profile(selection);analysis(s,schema,entries);var estimate=OntologyDiscovery.estimate(entries,json);
        var plan=new OntologyDiscovery.Plan(estimate.budget().allowed()?UUID.randomUUID().toString():"",schema,profile,estimate.batches(),entries.size(),Instant.now().plusSeconds(1200),estimate.budget());
        if(estimate.budget().allowed()){s.metadata().ontology().pipeline().prepare(plan,entries.stream().map(OntologyContext::reference).toList());return s.metadata().ontology().pipeline().preview();}
        return new OntologyDiscovery.Preview("",schema,profile,entries.size(),plan.expires(),plan.budget(),null,OntologyDiscovery.GROUP_CALLS);
    });}}
    public OntologyDiscovery.Preview status(PoolSession s,String schema){synchronized(s){scope(s,schema);var state=s.metadata().ontology().pipeline();return state.plan()!=null&&state.plan().schema().equals(schema)?state.preview():null;}}
    public OntologyDiscovery.Batch payload(PoolSession s,String token,int index){synchronized(s){
        var plan=s.metadata().ontology().pipeline().plan();if(plan==null||!plan.token().equals(token)||index<0||index>=plan.batches().size())throw new Failure(409,"stale");scope(s,plan.schema());return plan.batches().get(index);
    }}
    private void profileCurrent(PoolSession s,OntologyDiscovery.Plan plan){
        if(!plan.profile().selection().equals(s.metadata().assistant().selected())||!plan.profile().equals(ai.profile(plan.profile().selection())))throw new Failure(409,"stale");
    }
    public OntologyDiscovery.Preview resume(PoolSession s,String token,int index,boolean consent){synchronized(s){
        if(!consent)throw new Failure(400,"confirmRequired");var state=s.metadata().ontology().pipeline();var plan=state.plan();
        if(plan==null||!plan.token().equals(token))throw new Failure(409,"stale");scope(s,plan.schema());
        if(state.progress().running())throw new Failure(409,"discovery.busy");
        return query(s,read,()->{current(s,plan,selectedEntries(s,plan.schema(),selectedTables(s)));profileCurrent(s,plan);
            state.authorize(token,index,true,plan.schema(),Instant.now());return state.preview();});
    }}
    private void current(PoolSession s,OntologyDiscovery.Plan plan,List<Entry> entries){
        var state=s.metadata().ontology().pipeline();if(state.plan()!=plan)throw new Failure(409,"stale");
        var refs=entries.stream().map(OntologyContext::reference).toList();if(!refs.equals(state.references()))throw new Failure(409,"stale");
    }
    public OntologyDiscovery.Step generate(PoolSession s,String token,int index,boolean consent,Locale locale){
        final OntologyDiscovery.Batch batch;final OntologyDiscovery.Plan plan;final AiAssistant.Draft draft;
        var state=s.metadata().ontology().pipeline();var assistant=s.metadata().assistant();
        synchronized(s){
            plan=state.plan();if(plan==null)throw new Failure(409,"stale");scope(s,plan.schema());
            batch=state.begin(token,index,consent,s.metadata().selectedSchema(),Instant.now());
            try{var preview=assistant.prepare(plan.schema(),plan.profile(),"RELATION_DISCOVERY",batch.source(),false,locale.getLanguage(),Instant.now(),"relationships");draft=assistant.consume(preview.token(),consent,plan.schema(),Instant.now(),"relationships");}
            catch(RuntimeException ex){state.finish(List.of(),false);throw ex;}
        }
        boolean success=false;
        try{
            var rows=query(s,generate,()->{
                final List<String> selected;synchronized(s){selected=selectedTables(s);}
                var entries=selectedEntries(s,plan.schema(),selected);synchronized(s){scope(s,plan.schema());current(s,plan,entries);}
                synchronized(s){profileCurrent(s,plan);}
                var context=analysis(s,plan.schema(),entries); // Fail local validation before any billable call.
                var output=ai.explain(profiles.packageOwner(plan.profile().selection().owner()),plan.profile().selection().name(),OntologyDiscovery.prompt(batch,draft.language()));
                var result=OntologyDiscovery.parse(output,batch,context,plan.profile(),json);
                synchronized(s){scope(s,plan.schema());current(s,plan,selectedEntries(s,plan.schema(),selected));profileCurrent(s,plan);}return result;
            });
            synchronized(s){if(state.plan()!=plan)throw new Failure(409,"stale");state.finish(rows,true);success=true;return state.progress();}
        }finally{if(!success)state.finish(List.of(),false);assistant.finish();}
    }
    public void stop(PoolSession s,String token){synchronized(s){s.metadata().ontology().pipeline().stop(token);}}
    public PropertyGraph.Preview graphPreview(PoolSession s,String schema,String name){synchronized(s){scope(s,schema);return query(s,read,()->{
        var entries=entries(s,schema);var definition=PropertyGraph.build(schema,name,entries,analysis(s,schema,entries));var access=graphs.access(schema,login(s),definition.name());
        // Preview does not execute DDL, call AI, sample rows or query external data.
        var draft=new PropertyGraph.Draft(UUID.randomUUID().toString(),schema,definition,entries,Instant.now().plusSeconds(600));s.metadata().ontology().pipeline().graph(draft);
        return new PropertyGraph.Preview(draft.token(),definition,access,draft.expires(),access.allowed()&&definition.vertices()>0);
    });}}
    public PropertyGraph.Created create(PoolSession s,String token,boolean confirmed){synchronized(s){
        var schema=s.metadata().selectedSchema();var draft=s.metadata().ontology().pipeline().consumeGraph(token,schema,confirmed,Instant.now());scope(s,schema);
        return query(s,ddl,()->{
            if(!graphs.access(schema,login(s),draft.definition().name()).allowed())throw new Failure(409,"pg.unavailable");
            var now=entries(s,schema);if(!PropertyGraph.sameDefinitions(draft.entries(),now))throw new Failure(409,"stale");
            var definition=PropertyGraph.build(schema,draft.definition().name(),now,analysis(s,schema,now));
            if(definition.sql().isEmpty()||!definition.equals(draft.definition()))throw new Failure(409,"stale");
            graphs.verify(now,definition);return graphs.create(definition);
        });
    }}
}
