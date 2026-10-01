package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.NativeMetadata.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NativeMetadataService {
    private final SessionDataSource source;
    private final NativeMetadataRepository rdf;
    private final OntologyRepository catalog;
    private final TransactionTemplate read,write;
    public NativeMetadataService(SessionDataSource source,NativeMetadataRepository rdf,OntologyRepository catalog){
        this.source=source;this.rdf=rdf;this.catalog=catalog;
        var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(120);write=new TransactionTemplate(manager);write.setTimeout(120);
    }
    private String login(PoolSession s){return s.metadata().info().username();}
    private void scope(PoolSession s,String schema){
        Ontology.name(schema);if(!schema.equals(s.metadata().selectedSchema()))throw new Ontology.Failure(409,"stale");
        if(!schema.equals(login(s)))throw new Ontology.Failure(403,"ownerRequired");
    }
    private <T>T tx(PoolSession s,TransactionTemplate template,Supplier<T> action){source.bind(s.pool(),login(s));try{return template.execute(status->action.get());}finally{source.clear();}}
    private State state(PoolSession s){return s.metadata().ontology().nativeMetadata();}
    public Status status(PoolSession s,String schema){synchronized(s){
        scope(s,schema);return tx(s,read,()->{String storage=rdf.storage();return new Status(storage,true,rdf.api(),storage.equals("READY")?rdf.snapshots():List.of());});
    }}
    public Map<String,String> scripts(PoolSession s,String schema){synchronized(s){
        scope(s,schema);var scripts=new LinkedHashMap<String,String>();for(String file:List.of("setup","capture","read","snapshots","candidates"))scripts.put(file,NativeMetadataRepository.sql(file));return scripts;
    }}
    public Status install(PoolSession s,String schema,boolean confirmed){synchronized(s){
        scope(s,schema);if(!confirmed)throw new Ontology.Failure(400,"confirmRequired");
        tx(s,write,()->{rdf.install();return true;});state(s).clear();return status(s,schema);
    }}
    private Ontology.Entry required(String schema,String name){
        Ontology.name(name);var entry=catalog.entry(schema,name,0);if(entry==null)throw new Ontology.Failure(409,"scope.missingDefinition");return entry;
    }
    public Capture capture(PoolSession s,String schema,String table,boolean confirmed){synchronized(s){
        scope(s,schema);if(!confirmed)throw new Ontology.Failure(400,"confirmRequired");state(s).clear();
        return tx(s,write,()->{catalog.require(schema,login(s));required(schema,table);return rdf.capture(table);});
    }}
    public List<Map<String,String>> columns(PoolSession s,String schema,String table){synchronized(s){
        scope(s,schema);Ontology.name(table);return tx(s,read,()->{rdf.require();var snapshot=rdf.snapshots().stream().filter(v->v.table().equals(table)).findFirst().orElseThrow(()->new Ontology.Failure(404,"notFound"));return rdf.columns(snapshot.iri());});
    }}
    private List<Snapshot> selection(List<Snapshot> available,List<String> names){
        var values=new LinkedHashMap<String,Snapshot>();available.forEach(v->values.put(v.table(),v));
        return names.stream().map(name->{var v=values.get(name);if(v==null)throw new Ontology.Failure(409,"native.captureFirst");return v;}).toList();
    }
    public Preview preview(PoolSession s,String schema,List<String> tables){synchronized(s){
        scope(s,schema);var names=NativeMetadata.names(tables,2);state(s).clear();
        return tx(s,read,()->{
            rdf.require();catalog.require(schema,login(s));var snapshots=selection(rdf.snapshots(),names);
            var entries=new LinkedHashMap<String,Ontology.Entry>();names.forEach(n->entries.put(n,required(schema,n)));
            var byName=new HashMap<String,Snapshot>();snapshots.forEach(v->byName.put(v.table(),v));
            var relations=new ArrayList<OntologyRelations.Relation>();int existing=0;
            for(var match:rdf.candidates(snapshots)){
                if(!byName.containsKey(match.source())||!byName.containsKey(match.target())||!byName.get(match.source()).iri().equals(match.sourceSnapshot())||!byName.get(match.target()).iri().equals(match.targetSnapshot()))throw new Ontology.Failure(409,"native.mismatch");
                var a=entries.get(match.source());var b=entries.get(match.target());
                // Only membership/staleness checks here. Matching itself is in candidates.sql.
                for(var pair:List.of(Map.entry(a,match.sourceColumn()),Map.entry(b,match.targetColumn()))){
                    if(pair.getKey().document().source().columns().stream().noneMatch(c->c.name().equals(pair.getValue())&&NativeMetadata.baseType(c.dataType()).equals(match.type())))throw new Ontology.Failure(409,"native.metadataChanged");
                }
                var from=List.of(match.sourceColumn());var to=List.of(match.targetColumn());
                String id=OntologyRelations.id(match.source(),schema,match.target(),from,to),reverse=OntologyRelations.id(match.target(),schema,match.source(),to,from);
                if(a.document().links().stream().anyMatch(v->v.id().equals(id))||b.document().links().stream().anyMatch(v->v.id().equals(reverse))){existing++;continue;}
                relations.add(new OntologyRelations.Relation(id,match.source(),schema,match.target(),from,to,"CANDIDATE","RDF","","",
                    List.of("ORACLE_RDF","NAME","COMPATIBLE_TYPE","UNVERIFIED_JOIN","SOURCE_RDF:"+match.sourceSnapshot(),"TARGET_RDF:"+match.targetSnapshot()),null,null));
            }
            var preview=new Preview(UUID.randomUUID().toString(),schema,snapshots,relations,existing,Instant.now().plusSeconds(600));state(s).prepare(new Pending(preview,entries));return preview;
        });
    }}
    public Saved save(PoolSession s,String schema,String token,List<String> ids,boolean confirmed){synchronized(s){
        scope(s,schema);if(ids==null||ids.isEmpty()||ids.size()>NativeMetadata.MAX_CANDIDATES||new HashSet<>(ids).size()!=ids.size())throw new Ontology.Failure(400,"invalid");
        var pending=state(s).consume(schema,token,confirmed,Instant.now());var selected=new HashSet<>(ids);
        var candidates=pending.preview().candidates().stream().filter(v->selected.contains(v.id())).toList();if(candidates.size()!=ids.size())throw new Ontology.Failure(409,"stale");
        try{return tx(s,write,()->{
            rdf.require();catalog.require(schema,login(s));
            var snapshots=selection(rdf.snapshots(),pending.preview().snapshots().stream().map(Snapshot::table).toList());
            if(!snapshots.equals(pending.preview().snapshots()))throw new Ontology.Failure(409,"stale");
            var entries=new LinkedHashMap<String,Ontology.Entry>();
            pending.entries().forEach((name,before)->{var current=required(schema,name);if(!before.equals(current))throw new Ontology.Failure(409,"stale");entries.put(name,current);});
            int saved=0;var changed=new ArrayList<String>();
            for(var before:entries.values()){
                var rows=candidates.stream().filter(v->v.source().equals(before.document().source().table())).toList();if(rows.isEmpty())continue;
                var document=OntologyRelations.candidates(before,entries,rows,login(s),Instant.now().toString());
                int added=document.links().size()-before.document().links().size();if(added==0)continue;
                catalog.append(schema,before.document().source().table(),before.revision(),"DRAFT",document);saved+=added;changed.add(before.document().source().table());
            }
            return new Saved(saved,candidates.size()-saved,changed);
        });}finally{s.metadata().ontology().clear(schema);}
    }}
}
