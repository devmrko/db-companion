package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyWizard.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OntologyWizardService {
    private final SessionDataSource source;private final OntologyRepository repository;private final OntologyWizardRepository samples;
    private final AiAssistantRepository ai;private final ProfileHistoryRepository profiles;private final JsonMapper json;
    private final TransactionTemplate read,write,generate;
    public OntologyWizardService(SessionDataSource source,OntologyRepository repository,OntologyWizardRepository samples,AiAssistantRepository ai,ProfileHistoryRepository profiles,JsonMapper json){
        this.source=source;this.repository=repository;this.samples=samples;this.ai=ai;this.profiles=profiles;this.json=json;
        var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(15);write=new TransactionTemplate(manager);write.setTimeout(15);generate=new TransactionTemplate(manager);generate.setReadOnly(true);generate.setTimeout(90);
    }
    private void scope(PoolSession s,String schema){Ontology.name(schema);if(!schema.equals(s.metadata().selectedSchema()))throw new Failure(409,"stale");}
    private <T>T query(PoolSession s,TransactionTemplate tx,Supplier<T> work){source.bind(s.pool(),s.metadata().info().username());try{return tx.execute(status->work.get());}finally{source.clear();}}
    private Entry entry(PoolSession s,String schema,String table,int revision){
        Ontology.name(table);if(revision<1)throw new Failure(400,"invalid");repository.require(schema,s.metadata().info().username());
        var e=repository.entry(schema,table,0);if(e==null||e.revision()!=revision)throw new Failure(409,"stale");return e;
    }
    public List<Map<String,String>> options(PoolSession s,String schema,String table,int revision){synchronized(s){scope(s,schema);return query(s,read,()->{
        var e=entry(s,schema,table,revision);return e.document().source().columns().stream().map(c->Map.of("name",c.name(),"type",c.dataType(),"blocked",OntologyWizard.blocked(c,e.document().meaning().columns().get(c.name())),"sensitivity",e.document().meaning().columns().get(c.name()).sensitivity())).toList();
    });}}
    public OntologyWizard.Preview sample(PoolSession s,String schema,String table,int revision,List<String> columns,int count,boolean confirmed,Locale locale){synchronized(s){
        scope(s,schema);if(!confirmed)throw new Failure(400,"wizard.confirm");
        return query(s,read,()->{
            var e=entry(s,schema,table,revision);var selected=OntologyWizard.select(e,columns,confirmed,count);var assistant=s.metadata().assistant();
            if(assistant.selected()==null)throw new Failure(409,"chooseProfile");var profile=ai.profile(assistant.selected());
            var sample=OntologyWizard.sanitize(e,selected,samples.sample(e.document().source(),selected,count),Instant.now());
            var request=assistant.prepare(schema,profile,table,OntologyWizard.payload(sample,e,json),false,locale.getLanguage(),Instant.now(),"ontology-wizard");
            var preview=new OntologyWizard.Preview(request.token(),sample,request);s.metadata().ontology().wizard().prepare(preview);return preview;
        });
    }}
    public Proposal generate(PoolSession s,String token,boolean consent){
        final OntologyWizard.Preview preview;final AiAssistant.Draft draft;final AiAssistant.State assistant;
        synchronized(s){assistant=s.metadata().assistant();draft=assistant.consume(token,consent,s.metadata().selectedSchema(),Instant.now(),"ontology-wizard");
            try{preview=s.metadata().ontology().wizard().consume(token,Instant.now());}catch(RuntimeException e){assistant.finish();throw e;}}
        try{return query(s,generate,()->{
            var sample=preview.sample();var e=entry(s,sample.schema(),sample.table(),sample.revision());
            var columns=OntologyWizard.select(e,sample.columns().stream().map(SampleColumn::name).toList(),true,10);samples.verify(e.document().source(),columns);
            var profile=draft.preview().profile();if(!profile.equals(ai.profile(profile.selection())))throw new Failure(409,"stale");
            String name=profile.selection().owner()+"."+profile.selection().name();
            String output=ai.explain(profiles.packageOwner(profile.selection().owner()),profile.selection().name(),OntologyWizard.prompt(draft));
            var proposal=new Proposal(UUID.randomUUID().toString(),sample.schema(),sample.table(),sample.revision(),OntologyWizard.parse(output,sample,name,json),Instant.now().plusSeconds(600));
            synchronized(s){scope(s,sample.schema());s.metadata().ontology().wizard().propose(proposal);}return proposal;
        });}finally{assistant.finish();}
    }
    public Entry apply(PoolSession s,String schema,String table,int revision,String token,List<Edit> edits){synchronized(s){
        scope(s,schema);var proposal=s.metadata().ontology().wizard().use(token,schema,table,revision,Instant.now());
        try{return query(s,write,()->{var e=entry(s,schema,table,revision);var meaning=OntologyWizard.merge(e,proposal,edits);
            return repository.append(schema,table,revision,"DRAFT",new Document(1,e.document().source(),meaning,"AI_WIZARD_REVIEWED",proposal.columns().getFirst().definition().profile(),e.document().analysis(),e.document().links()));
        });}finally{s.metadata().ontology().clear(schema);}
    }}
    public void cancel(PoolSession s,String token){synchronized(s){s.metadata().ontology().wizard().cancel(token);s.metadata().assistant().discard(token);}}
}
