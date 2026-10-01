package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyGovernance.Difference;
import com.dbcompanion.repository.OntologyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Metadata-only import. Profile selection is a UI scope, never ontology ownership. */
@Service
public class OntologyImportService {
    public record Preview(String table,String outcome,int revision,String token,List<Difference> differences,String reason) {
        public Preview { differences=List.copyOf(differences); }
    }
    private record Pending(String schema,String table,int revision,String documentId,String fingerprint,Instant expires) {}
    private final Map<PoolSession,Map<String,Pending>> pending=Collections.synchronizedMap(new WeakHashMap<>());
    private final SessionDataSource source;
    private final OntologyRepository repository;
    private final JsonMapper json;
    private final TransactionTemplate read,write;

    public OntologyImportService(SessionDataSource source,OntologyRepository repository,JsonMapper json) {
        this.source=source;this.repository=repository;this.json=json;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(Ontology.READ_TIMEOUT_SECONDS);
        write=new TransactionTemplate(manager);write.setTimeout(Ontology.READ_TIMEOUT_SECONDS);
    }
    private void scope(PoolSession session,String schema,String table) {
        Ontology.name(schema);Ontology.name(table);
        if(!schema.equals(session.metadata().selectedSchema()))throw new Failure(409,"stale");
        if(OntologySql.TABLE.equals(table))throw new Failure(400,"invalid");
    }
    private <T>T query(PoolSession session,TransactionTemplate tx,Supplier<T> action) {
        source.bind(session.pool(),session.metadata().info().username());
        try{return tx.execute(status->JdbcNetworkTimeout.execute(source,40_000,action));}finally{source.clear();}
    }
    private void require(PoolSession session,String schema) {repository.require(schema,session.metadata().info().username());}
    private Snapshot snapshot(PoolSession session,String schema,String table) {return repository.snapshot(session.metadata().info().database(),schema,table);}
    public Preview preview(PoolSession session,String schema,String table) {synchronized(session){
        scope(session,schema,table);
        var tokens=pending.computeIfAbsent(session,key->new LinkedHashMap<>());
        tokens.values().removeIf(p->!p.expires().isAfter(Instant.now())||p.schema().equals(schema)&&p.table().equals(table));
        return query(session,read,()->{
            require(session,schema);var before=repository.entry(schema,table,0);var current=snapshot(session,schema,table);
            int revision=before==null?0:before.revision();
            var differences=before==null?List.<Difference>of():differences(before,current);
            if(!"SUCCESS".equals(current.annotationStatus()))return new Preview(table,"REVIEW",revision,"",differences,"annotations");
            boolean unchanged=before!=null&&sameMetadata(before.document().source(),current);
            if(before!=null&&losesDefinition(before,current))return new Preview(table,"REVIEW",revision,"",differences,"removed");
            try{document(before,current);}catch(Failure ex){return new Preview(table,"REVIEW",revision,"",differences,"definition");}
            // Bound session memory; replaced previews cannot later authorize a write.
            if(tokens.size()>=100)tokens.remove(tokens.keySet().iterator().next());
            String token=UUID.randomUUID().toString();
            tokens.put(token,new Pending(schema,table,revision,before==null?"":before.documentId(),fingerprint(current),Instant.now().plusSeconds(600)));
            return new Preview(table,before==null?"NEW":unchanged?"UNCHANGED":"CHANGED",revision,token,differences,"");
        });
    }}
    public CaptureResult apply(PoolSession session,String schema,String table,String token,boolean confirmed) {synchronized(session){
        scope(session,schema,table);if(!confirmed)throw new Failure(400,"confirmRequired");
        var tokens=pending.get(session);var checked=tokens==null?null:tokens.remove(token);
        if(checked==null||!checked.schema().equals(schema)||!checked.table().equals(table)||!checked.expires().isAfter(Instant.now()))throw new Failure(409,"stale");
        try{return query(session,write,()->{
            require(session,schema);var before=repository.entry(schema,table,0);
            if((before==null?0:before.revision())!=checked.revision()||!Objects.equals(before==null?"":before.documentId(),checked.documentId()))throw new Failure(409,"stale");
            var current=snapshot(session,schema,table);
            if(!"SUCCESS".equals(current.annotationStatus())||!fingerprint(current).equals(checked.fingerprint())||before!=null&&losesDefinition(before,current))throw new Failure(409,"stale");
            var saved=repository.append(schema,table,checked.revision(),"DRAFT",document(before,current));
            return new CaptureResult(table,"IMPORTED",saved.revision(),OntologyRdf.export(saved).triples().size());
        });}finally{session.metadata().ontology().clear(schema);}
    }}
    private Document document(Entry before,Snapshot current) {
        if(before==null)return new Document(1,current,Ontology.validate(current,Ontology.initial(current)),"CATALOG",null);
        var old=before.document();
        return new Document(1,current,OntologyGovernanceService.rebase(old.meaning(),current),"METADATA_REFRESH",old.profile(),old.analysis(),old.links());
    }
    private static boolean losesDefinition(Entry before,Snapshot current) {
        var old=before.document().meaning();var fresh=Ontology.initial(current);
        return !fresh.columns().keySet().containsAll(old.columns().keySet())||!fresh.relations().keySet().containsAll(old.relations().keySet());
    }
    private List<Difference> differences(Entry before,Snapshot current) {
        var changes=new ArrayList<>(OntologyGovernanceService.compare(before,current).differences());
        for(var old:before.document().source().columns())for(var now:current.columns())
            if(old.name().equals(now.name())&&old.position()!=now.position())changes.add(new Difference("POSITION",old.name(),""+old.position(),""+now.position(),"REVIEW_REQUIRED"));
        if(!Objects.equals(before.document().source().annotationStatus(),current.annotationStatus()))changes.add(new Difference("ANNOTATION_STATUS",current.table(),before.document().source().annotationStatus(),current.annotationStatus(),"REVIEW_REQUIRED"));
        return changes;
    }
    private boolean sameMetadata(Snapshot a,Snapshot b) {return fingerprint(a).equals(fingerprint(b));}
    private String fingerprint(Snapshot snapshot) {
        var stable=new Snapshot(snapshot.database(),snapshot.schema(),snapshot.table(),snapshot.comment(),snapshot.columns(),snapshot.keys(),"",snapshot.annotations(),snapshot.annotationStatus());
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(stable).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
