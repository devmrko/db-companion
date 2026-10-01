package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyDiscoveryArchive.*;
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
    private final TransactionTemplate read,generate,ddl,write;private final OntologyDiscoveryRepository archive;
    public OntologyPipelineService(SessionDataSource source,OntologyRepository repository,AiAssistantRepository ai,ProfileHistoryRepository profiles,PropertyGraphRepository graphs,JsonMapper json){
        this(source,repository,ai,profiles,graphs,json,new OntologyDiscoveryRepository(new org.springframework.jdbc.core.JdbcTemplate(source),new AppRecordRepository(new org.springframework.jdbc.core.JdbcTemplate(source),json),json));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public OntologyPipelineService(SessionDataSource source,OntologyRepository repository,AiAssistantRepository ai,ProfileHistoryRepository profiles,PropertyGraphRepository graphs,JsonMapper json,OntologyDiscoveryRepository archive){
        this.source=source;this.repository=repository;this.ai=ai;this.profiles=profiles;this.graphs=graphs;this.json=json;
        this.archive=archive;
        var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(60);
        generate=new TransactionTemplate(manager);generate.setReadOnly(true);generate.setTimeout(90);ddl=new TransactionTemplate(manager);ddl.setTimeout(60);write=new TransactionTemplate(manager);write.setTimeout(60);
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
        return resume(s,token,index,consent,false);
    }}
    public OntologyDiscovery.Preview resume(PoolSession s,String token,int index,boolean consent,boolean all){synchronized(s){
        if(!consent)throw new Failure(400,"confirmRequired");var state=s.metadata().ontology().pipeline();var plan=state.plan();
        if(plan==null||!plan.token().equals(token))throw new Failure(409,"stale");scope(s,plan.schema());
        if(state.progress().running())throw new Failure(409,"discovery.busy");
        query(s,write,()->{var entries=selectedEntries(s,plan.schema(),selectedTables(s));current(s,plan,entries);profileCurrent(s,plan);archive.require(plan.schema(),login(s));
            if(!archive.exists(plan.schema(),token)){var definitions=new LinkedHashMap<String,String>();entries.forEach(e->definitions.put(e.document().source().table(),OntologyRelations.definitionHash(e.document())));
                archive.saveRun(new Run(1,token,s.metadata().info().database(),plan.schema(),plan.profile(),entries.stream().map(OntologyContext::reference).toList(),definitions,entries.size(),plan.batches().size(),Instant.now().toString()));}
            return true;});
        state.authorize(token,index,true,plan.schema(),Instant.now(),all);return state.preview();
    }}
    public Page saved(PoolSession s,String schema,String before){synchronized(s){scope(s,schema);return query(s,read,()->{archive.require(schema,login(s));return archive.page(schema,s.metadata().info().database(),before);});}}
    public List<OntologyDiscoveryArchive.Summary> calls(PoolSession s,String schema,String token){synchronized(s){scope(s,schema);return query(s,read,()->{archive.require(schema,login(s));archive.run(schema,s.metadata().info().database(),token);return archive.calls(schema,token).stream().map(c->new OntologyDiscoveryArchive.Summary(c.receipt().index(),c.state(),c.receipt().stage(),"SUCCEEDED".equals(c.state())?c.receipt().relations().size():0,c.receipt().issues())).toList();});}}
    public Call call(PoolSession s,String schema,String token,int index){synchronized(s){scope(s,schema);return query(s,read,()->{archive.require(schema,login(s));archive.run(schema,s.metadata().info().database(),token);return archive.call(schema,token,index);});}}
    public OntologyDiscovery.Preview restore(PoolSession s,String schema,String token){synchronized(s){scope(s,schema);return query(s,read,()->{
        archive.require(schema,login(s));var run=archive.run(schema,s.metadata().info().database(),token);var originals=new ArrayList<Entry>();
        for(var ref:run.sources()){var e=repository.entry(schema,ref.table(),ref.revision());if(e==null||!e.documentId().equals(ref.documentId()))throw new Failure(409,"stale");originals.add(e);}
        var now=selectedEntries(s,schema,run.sources().stream().map(OntologyAnalysis.Reference::table).toList());
        if(now.size()!=run.sources().size()||now.stream().anyMatch(e->!Objects.equals(run.definitions().get(e.document().source().table()),OntologyRelations.definitionHash(e.document()))))throw new Failure(409,"stale");
        var estimate=OntologyDiscovery.estimate(originals,json);if(!estimate.budget().allowed()||estimate.batches().size()!=run.calls())throw new Failure(409,"stale");
        var plan=new OntologyDiscovery.Plan(token,schema,run.profile(),estimate.batches(),originals.size(),Instant.now().plusSeconds(1200),estimate.budget());
        var state=s.metadata().ontology().pipeline();state.restore(plan,now,archive.calls(schema,token));return state.preview();
    });}}
    public OntologyDiscovery.Preview recover(PoolSession s,String schema,String token,int index,boolean confirmed){synchronized(s){
        if(!confirmed)throw new Failure(400,"confirmRequired");restore(s,schema,token);var plan=s.metadata().ontology().pipeline().plan();
        query(s,write,()->{var call=archive.call(schema,token,index);var receipt=call.receipt();if(!"CHECK_REQUIRED".equals(call.state())||!"SAVE".equals(receipt.stage())||receipt.raw().isEmpty())throw new Failure(409,"stale");
            var entries=selectedEntries(s,schema,selectedTables(s));current(s,plan,entries);var parsed=OntologyDiscovery.inspect(receipt.raw(),plan.batches().get(index),analysis(s,schema,entries),plan.profile(),json);
            saveCandidates(s,schema,entries,parsed.relations());archive.recovered(schema,new Receipt(token,index,"SAVED",receipt.raw(),parsed.relations(),parsed.issues()));return true;});
        s.metadata().ontology().clearReview(schema);return restore(s,schema,token);
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
        boolean settled=false,claimed=false;String output="",stage="PREPARE";OntologyDiscovery.Result result=new OntologyDiscovery.Result(List.of(),List.of());
        try{
            query(s,read,()->{
                final List<String> selected;synchronized(s){selected=selectedTables(s);}
                var entries=selectedEntries(s,plan.schema(),selected);synchronized(s){scope(s,plan.schema());current(s,plan,entries);}
                synchronized(s){profileCurrent(s,plan);}
                analysis(s,plan.schema(),entries);return true; // Fail local validation before any billable call.
            });
            query(s,write,()->{archive.require(plan.schema(),login(s));archive.run(plan.schema(),s.metadata().info().database(),token);archive.claim(plan.schema(),new Receipt(token,index,"AI","",List.of(),List.of()));return true;});claimed=true;stage="AI";
            output=query(s,generate,()->ai.explain(profiles.packageOwner(plan.profile().selection().owner()),plan.profile().selection().name(),OntologyDiscovery.prompt(batch,draft.language())));
            stage="VALIDATION";final String received=output;
            result=query(s,read,()->OntologyDiscovery.inspect(received,batch,analysis(s,plan.schema(),selectedEntries(s,plan.schema(),selectedTables(s))),plan.profile(),json));
            stage="SAVE";final var parsed=result;
            var saved=query(s,write,()->{
                synchronized(s){scope(s,plan.schema());var entries=selectedEntries(s,plan.schema(),selectedTables(s));current(s,plan,entries);profileCurrent(s,plan);
                    var stored=saveCandidates(s,plan.schema(),entries,parsed.relations());archive.finish(plan.schema(),new Receipt(token,index,"SAVED",received,parsed.relations(),parsed.issues()),"SUCCEEDED");return stored;}
            });
            synchronized(s){state.persisted(saved);state.recorded(result.relations(),result.issues().isEmpty());s.metadata().ontology().clearReview(plan.schema());settled=true;return state.progress();}
        }catch(RuntimeException ex){
            if(!claimed)throw ex;
            var issues=ex instanceof OntologyDiscovery.ResponseFailure failure?List.of(new OntologyDiscovery.Issue(failure.diagnostic().code(),failure.diagnostic().path())):result.issues();
            var receipt=new Receipt(token,index,stage,output!=null&&output.length()<=AiAssistant.MAX_RESULT?output:"",result.relations(),issues,ex instanceof Failure?ex.getMessage():CredentialCatalogRepository.error(ex));
            query(s,write,()->{archive.finish(plan.schema(),receipt,"VALIDATION".equals(receipt.stage())?"FAILED":"CHECK_REQUIRED");return true;});
            synchronized(s){state.recorded(List.of(),false);if("SAVE".equals(stage))state.stop(token);settled=true;return state.progress();}
        }finally{if(!settled)state.finish(List.of(),false);assistant.finish();}
    }
    private List<Entry> saveCandidates(PoolSession s,String schema,List<Entry> entries,List<OntologyRelations.Relation> rows){
        var byName=new LinkedHashMap<String,Entry>();entries.forEach(e->byName.put(e.document().source().table(),e));
        for(var before:entries){String table=before.document().source().table();var candidates=rows.stream().filter(r->r.source().equals(table)).toList();if(candidates.isEmpty())continue;
            var doc=OntologyRelations.candidates(before,byName,candidates,login(s),Instant.now().toString());
            if(!doc.equals(before.document()))byName.put(table,repository.append(schema,table,before.revision(),before.state(),doc));
        }
        return List.copyOf(byName.values());
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
