package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.MetadataGraph.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MetadataGraphService {
    private final SessionDataSource source;private final OntologyRepository catalog;private final MetadataGraphRepository graphs;
    private final TransactionTemplate read,ddl;
    public MetadataGraphService(SessionDataSource source,OntologyRepository catalog,MetadataGraphRepository graphs){
        this.source=source;this.catalog=catalog;this.graphs=graphs;var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(120);ddl=new TransactionTemplate(manager);ddl.setTimeout(180);
    }
    private String login(PoolSession s){return s.metadata().info().username();}
    private void scope(PoolSession s,String schema){Ontology.name(schema);if(!schema.equals(s.metadata().selectedSchema()))throw new Ontology.Failure(409,"stale");if(!schema.equals(login(s)))throw new Ontology.Failure(403,"ownerRequired");}
    private <T>T tx(PoolSession s,TransactionTemplate template,Supplier<T> action){source.bind(s.pool(),login(s));try{return template.execute(status->action.get());}finally{source.clear();}}
    private MetadataGraph.State state(PoolSession s){return s.metadata().ontology().metadataGraph();}
    private Plan plan(PoolSession s,String schema,String name,List<Ontology.Entry> entries){return MetadataGraph.build(schema,name,entries,OntologyRelations.analyze(s.metadata().info().database(),schema,entries,Instant.now().toString()));}
    public Preview preview(PoolSession s,String schema,String name){synchronized(s){
        scope(s,schema);state(s).clear();return tx(s,read,()->{
            var entries=catalog.relationshipEntries(schema,login(s));var plan=plan(s,schema,name,entries);var access=graphs.access(plan,login(s));
            graphs.verifyProjection(plan);
            var preview=new Preview(UUID.randomUUID().toString(),plan,access,Instant.now().plusSeconds(600),access.allowed());
            state(s).prepare(new Draft(preview,entries));return preview;
        });
    }}
    public Created create(PoolSession s,String schema,String token,boolean confirmed){synchronized(s){
        scope(s,schema);var draft=state(s).consume(schema,token,confirmed,Instant.now());
        return tx(s,ddl,()->{
            var entries=catalog.relationshipEntries(schema,login(s));if(!PropertyGraph.sameDefinitions(draft.entries(),entries))throw new Ontology.Failure(409,"stale");
            var current=plan(s,schema,draft.preview().plan().name(),entries);
            if(!current.equals(draft.preview().plan())||!graphs.access(current,login(s)).allowed())throw new Ontology.Failure(409,"pg.unavailable");
            graphs.verifyProjection(current);return graphs.create(current);
        });
    }}
}
