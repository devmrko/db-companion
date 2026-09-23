package com.dbcompanion.model;

import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyRelations.*;
import com.dbcompanion.service.OntologyDiscovery;
import java.time.Instant;
import java.util.*;

/** Per-login pending plans and unapproved suggestions. No credentials or persisted settings. */
public final class OntologyPipeline {
    private OntologyPipeline(){}
    public static final class State {
        private OntologyDiscovery.Plan plan;private List<OntologyAnalysis.Reference> references=List.of();
        private final Map<String,Relation> candidates=new LinkedHashMap<>();private final List<Integer> failed=new ArrayList<>();
        private int completed,nextIndex,authorizedUntil;private boolean running;private Instant expires=Instant.EPOCH;
        private PropertyGraph.Draft graph;
        public synchronized void clear(){plan=null;references=List.of();candidates.clear();failed.clear();completed=0;nextIndex=0;authorizedUntil=0;expires=Instant.EPOCH;graph=null;}
        public synchronized void prepare(OntologyDiscovery.Plan value,List<OntologyAnalysis.Reference> refs){if(running)throw new Failure(409,"discovery.busy");clear();plan=value;references=List.copyOf(refs);expires=value.expires();}
        public synchronized OntologyDiscovery.Plan plan(){return plan;}
        public synchronized List<OntologyAnalysis.Reference> references(){return references;}
        public synchronized OntologyDiscovery.Preview preview(){return plan==null?null:new OntologyDiscovery.Preview(plan.token(),plan.schema(),plan.profile(),plan.tables(),expires,plan.budget(),progress(),OntologyDiscovery.GROUP_CALLS);}
        public synchronized void authorize(String token,int index,boolean consent,String schema,Instant now){
            if(!consent)throw new Failure(400,"confirmRequired");if(running)throw new Failure(409,"discovery.busy");
            if(plan==null||!plan.token().equals(token)||!plan.schema().equals(schema)||index!=nextIndex||index>=plan.batches().size())throw new Failure(409,"stale");
            if(candidates.size()>=OntologyDiscovery.MAX_CANDIDATES)throw new Failure(413,"discovery.resultLimit");
            authorizedUntil=Math.min(nextIndex+OntologyDiscovery.GROUP_CALLS,plan.batches().size());expires=now.plusSeconds(1200);
        }
        public synchronized OntologyDiscovery.Batch begin(String token,int index,boolean consent,String schema,Instant now){
            if(!consent)throw new Failure(400,"confirmRequired");if(running)throw new Failure(409,"discovery.busy");
            if(plan==null||!plan.token().equals(token)||!plan.schema().equals(schema)||!now.isBefore(expires)||index!=nextIndex||index>=authorizedUntil)throw new Failure(409,"stale");
            var batch=plan.batches().get(index);running=true;nextIndex++;return batch;
        }
        public synchronized void finish(List<Relation> result,boolean success){
            if(!running)return;running=false;if(plan==null)return;
            if(!success){failed.add(nextIndex);authorizedUntil=nextIndex;return;}
            completed++;for(var row:result)candidates.putIfAbsent(row.id(),row);
            // Preserve the entire last response, rather than silently dropping overflow suggestions.
            if(candidates.size()>=OntologyDiscovery.MAX_CANDIDATES)authorizedUntil=nextIndex;
        }
        public synchronized void stop(String token){if(plan!=null&&plan.token().equals(token))authorizedUntil=nextIndex;}
        public synchronized void reviewed(Entry before,Entry after){
            graph=null;if(plan==null)return;
            if(!before.documentId().equals(after.documentId())||!before.document().source().equals(after.document().source())||!before.document().meaning().equals(after.document().meaning())){clear();return;}
            references=references.stream().map(r->r.documentId().equals(before.documentId())&&r.revision()==before.revision()?new OntologyAnalysis.Reference(r.database(),r.schema(),r.table(),r.documentId(),after.revision(),after.state()):r).toList();
            var ids=new HashSet<String>();after.document().links().forEach(l->ids.add(l.id()));candidates.values().removeIf(r->r.source().equals(after.document().source().table())&&ids.contains(r.id()));
        }
        public synchronized OntologyDiscovery.Step progress(){int total=plan==null?0:plan.batches().size();return new OntologyDiscovery.Step(completed,total,candidates.size(),!running&&nextIndex>=total,nextIndex,authorizedUntil,running,!running&&nextIndex>=authorizedUntil,failed,candidates.size()>=OntologyDiscovery.MAX_CANDIDATES);}
        public synchronized Analysis augment(Analysis original){
            if(plan==null||!plan.schema().equals(original.schema()))return original;
            for(var ref:references){var table=original.tables().stream().filter(t->t.name().equals(ref.table())).findFirst();if(table.isEmpty()||!table.get().documentId().equals(ref.documentId())||table.get().revision()!=ref.revision())return original;}
            // A scoped run is reviewed in the full catalog; unrelated tables do not invalidate it.
            var rows=new ArrayList<>(original.relations());var ids=new HashSet<String>();rows.forEach(r->ids.add(r.id()));for(var r:candidates.values())if(ids.add(r.id()))rows.add(r);
            return new Analysis(original.schema(),original.tables(),rows,original.checkedAt());
        }
        public synchronized void graph(PropertyGraph.Draft value){graph=value;}
        public synchronized PropertyGraph.Draft consumeGraph(String token,String schema,boolean confirmed,Instant now){
            if(!confirmed)throw new Failure(400,"confirmRequired");if(graph==null||!graph.token().equals(token))throw new Failure(409,"stale");
            var value=graph;graph=null;if(!schema.equals(value.schema())||!now.isBefore(value.expires()))throw new Failure(409,"stale");return value;
        }
    }
}
