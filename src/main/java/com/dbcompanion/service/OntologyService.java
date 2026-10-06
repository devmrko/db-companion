package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.model.OntologyRelations;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyAnalysis.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OntologyService {
    private final SessionDataSource source;private final OntologyRepository repository;private final AiAssistantRepository ai;private final ProfileHistoryRepository profiles;private final JsonMapper json;
    private final TransactionTemplate read,write,generate;
    public OntologyService(SessionDataSource source,OntologyRepository repository,AiAssistantRepository ai,ProfileHistoryRepository profiles,JsonMapper json){
        this.source=source;this.repository=repository;this.ai=ai;this.profiles=profiles;this.json=json;
        var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(Ontology.READ_TIMEOUT_SECONDS);write=new TransactionTemplate(manager);write.setTimeout(15);generate=new TransactionTemplate(manager);generate.setReadOnly(true);generate.setTimeout(90);
    }
    private String login(PoolSession s){return s.metadata().info().username();}
    private void scope(PoolSession s,String schema){Ontology.name(schema);if(!schema.equals(s.metadata().selectedSchema()))throw new Failure(409,"stale");}
    private <T>T query(PoolSession s,TransactionTemplate tx,Supplier<T> action){source.bind(s.pool(),login(s),s.metadata().assistant());try{return tx.execute(status->tx==read?JdbcNetworkTimeout.execute(source,40_000,action):action.get());}finally{source.clear();}}
    public Catalog catalog(PoolSession s,String schema,boolean refresh){synchronized(s){scope(s,schema);var state=s.metadata().ontology();return state.catalog(schema,refresh,()->query(s,read,()->repository.catalog(schema,login(s),()->state.tables(schema,()->repository.tables(schema)))));}}
    public GraphData graph(PoolSession s,String schema,boolean refresh){synchronized(s){scope(s,schema);return s.metadata().ontology().graph(schema,refresh,()->query(s,read,()->repository.graph(s.metadata().info().database(),schema,login(s))));}}
    public OntologyRelations.Analysis relationships(PoolSession s,String schema){synchronized(s){scope(s,schema);return s.metadata().ontology().pipeline().augment(s.metadata().ontology().relationships(schema,()->query(s,read,()->analyze(s,schema))));}}
    private OntologyRelations.Analysis analyze(PoolSession s,String schema){return OntologyRelations.analyze(s.metadata().info().database(),schema,repository.relationshipEntries(schema,login(s)),Instant.now().toString());}
    public Entry reviewRelationship(PoolSession s,OntologyRelations.Review input){synchronized(s){
        scope(s,input.schema());Ontology.name(input.source());Ontology.name(input.target());
        try{return query(s,write,()->{
            var analysis=s.metadata().ontology().pipeline().augment(analyze(s,input.schema()));var before=required(input.schema(),input.source(),0);var target=required(input.schema(),input.target(),0);
            var link=OntologyRelations.review(input,before,target,analysis,login(s),Instant.now().toString());
            var after=repository.append(input.schema(),input.source(),input.sourceRevision(),"DRAFT",OntologyRelations.merge(before,link));s.metadata().ontology().pipeline().reviewed(before,after);return after;
        });}catch(RuntimeException ex){s.metadata().ontology().pipeline().clear();throw ex;}finally{s.metadata().ontology().clearReview(input.schema());}
    }}
    public Catalog install(PoolSession s,String schema,boolean confirmed){synchronized(s){scope(s,schema);if(!confirmed)throw new Failure(400,"confirmRequired");try{query(s,write,()->{repository.install(schema,login(s));return true;});}finally{s.metadata().ontology().clear(schema);}return catalog(s,schema,true);}}
    private Entry required(String schema,String table,int revision){Ontology.name(table);if(revision<0)throw new Failure(400,"invalid");var value=repository.entry(schema,table,revision);if(value==null)throw new Failure(404,"notFound");return value;}
    public Entry detail(PoolSession s,String schema,String table,int revision){synchronized(s){scope(s,schema);return query(s,read,()->{repository.require(schema,login(s));return required(schema,table,revision);});}}
    public Entry capture(PoolSession s,String schema,String table){synchronized(s){scope(s,schema);try{return query(s,write,()->{
        repository.require(schema,login(s));if(repository.entry(schema,table,0)!=null)throw new Failure(409,"stale");
        return appendCapture(s,schema,table);
    });}finally{s.metadata().ontology().clear(schema);}}}
    private Entry appendCapture(PoolSession s,String schema,String table){
        var snapshot=repository.snapshot(s.metadata().info().database(),schema,table);
        return repository.append(schema,table,0,"DRAFT",new Document(1,snapshot,Ontology.initial(snapshot),"CATALOG",null));
    }
    public CaptureResult captureMissing(PoolSession s,String schema,String table,boolean confirmed){
        synchronized(s){
            scope(s,schema);Ontology.name(table);
            if(!confirmed)throw new Failure(400,"confirmRequired");
            if(OntologySql.TABLE.equals(table))throw new Failure(400,"invalid");
            try{return query(s,write,()->{
                repository.require(schema,login(s));
                var saved=repository.entry(schema,table,0);
                boolean exists=saved!=null;
                if(!exists)saved=appendCapture(s,schema,table);
                // Verify the saved document's RDF before this table's transaction commits.
                int triples=OntologyRdf.export(saved).triples().size();
                return new CaptureResult(table,exists?"SKIPPED":"IMPORTED",saved.revision(),triples);
            });}finally{s.metadata().ontology().clear(schema);}
        }
    }
    public Entry save(PoolSession s,String schema,String table,int revision,Meaning meaning,String state){synchronized(s){scope(s,schema);if(revision<1)throw new Failure(400,"invalid");try{return query(s,write,()->{
        repository.require(schema,login(s));var before=required(schema,table,0);if(before.revision()!=revision)throw new Failure(409,"stale");
        return repository.append(schema,table,revision,state,new Document(1,before.document().source(),com.dbcompanion.model.OntologyWizard.manualMeaning(before,meaning),"USER",null,before.document().analysis(),before.document().links()));
    });}finally{s.metadata().ontology().clear(schema);}}}
    public History history(PoolSession s,String schema,String table,String before){synchronized(s){scope(s,schema);Ontology.name(table);return query(s,read,()->{repository.require(schema,login(s));return repository.history(schema,table,before);});}}
    public String rdf(PoolSession s,String schema,String table,int revision){return OntologyRdf.render(detail(s,schema,table,revision));}
    public OntologyRdf.Export rdfExport(PoolSession s,String schema,String table,int revision){if(revision<1)throw new Failure(400,"invalid");return OntologyRdf.export(detail(s,schema,table,revision));}
    public Preview preview(PoolSession s,String schema,String table,int revision,Locale locale){synchronized(s){scope(s,schema);return query(s,read,()->{
        repository.require(schema,login(s));var entry=required(schema,table,0);if(entry.revision()!=revision)throw new Failure(409,"stale");
        var assistant=s.metadata().assistant();var selected=assistant.selected();if(selected==null)throw new Failure(409,"chooseProfile");
        var profile=ai.profile(selected);
        var graph=repository.graph(s.metadata().info().database(),schema,login(s));
        var related=OntologyContext.neighbors(entry,graph).stream().map(name->required(schema,name,0)).toList();
        var bundle=OntologyContext.bundle(entry,related,json);
        var request=assistant.prepare(schema,profile,table,bundle.payload(),false,locale.getLanguage(),Instant.now(),"ontology");
        var preview=new Preview(request.token(),schema,table,revision,request,bundle.context());s.metadata().ontology().prepare(preview);return preview;
    });}}
    public Suggestion suggest(PoolSession s,String token,boolean consent){
        final Preview preview;final AiAssistant.Draft draft;final AiAssistant.State assistant;final Ontology.State state;
        synchronized(s){state=s.metadata().ontology();assistant=s.metadata().assistant();draft=assistant.consume(token,consent,s.metadata().selectedSchema(),Instant.now(),"ontology");
            try{preview=state.consume(token);}catch(RuntimeException ex){assistant.finish();throw ex;}}
        try{
            return query(s,generate,()->{
                repository.require(preview.schema(),login(s));var entry=required(preview.schema(),preview.table(),0);if(entry.revision()!=preview.revision())throw new Failure(409,"stale");
                verifyContext(s,preview.context());
                var profile=draft.preview().profile();if(!profile.equals(ai.profile(profile.selection())))throw new Failure(409,"stale");
                String output=ai.explain(profiles.packageOwner(profile.selection().owner()),profile.selection().name(),OntologyContext.prompt(draft));
                var recommendations=OntologyContext.parse(output,preview.context(),json);
                var result=new Suggestion(UUID.randomUUID().toString(),preview.schema(),preview.table(),preview.revision(),OntologyContext.merge(entry,recommendations),profile.selection().owner()+"."+profile.selection().name(),Instant.now().plusSeconds(600),preview.context(),recommendations,Instant.now().toString());
                synchronized(s){scope(s,preview.schema());state.suggest(result);}return result;
            });
        }finally{assistant.finish();}
    }
    private void verifyContext(PoolSession s,Context context){
        if(context==null||context.sources().isEmpty())throw new Failure(409,"stale");
        for(var ref:context.sources())if(!ref.schema().equals(s.metadata().selectedSchema())||!ref.database().equals(s.metadata().info().database())||!OntologyContext.same(ref,repository.entry(ref.schema(),ref.table(),0)))throw new Failure(409,"context.stale");
    }
    public Entry apply(PoolSession s,String schema,String table,int revision,String token,List<Edit> edits){synchronized(s){scope(s,schema);var proposal=s.metadata().ontology().use(token,schema,table,revision);
        try{return query(s,write,()->{repository.require(schema,login(s));var before=required(schema,table,0);if(before.revision()!=revision)throw new Failure(409,"stale");
            verifyContext(s,proposal.context());var selected=OntologyContext.selected(proposal.recommendations(),edits);
            return repository.append(schema,table,revision,"DRAFT",new Document(1,before.document().source(),OntologyContext.merge(before,selected),"AI_RDF_REVIEWED",proposal.profile(),new Evidence(proposal.context().sources(),selected,proposal.generatedAt()),before.document().links()));
        });}finally{s.metadata().ontology().clear(schema);}
    }}
    public void cancel(PoolSession s,String token){synchronized(s){s.metadata().ontology().cancel(token);s.metadata().assistant().discard(token);}}
}
