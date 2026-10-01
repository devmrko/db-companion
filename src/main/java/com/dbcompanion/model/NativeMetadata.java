package com.dbcompanion.model;

import java.time.Instant;
import java.util.*;

/** Native metadata capture and unapproved relation review; no domain-specific matching. */
public final class NativeMetadata {
    private NativeMetadata() { }
    public static final int MAX_OBJECTS=50, MAX_CANDIDATES=2000;
    public record Snapshot(String iri,String table,String kind,String capturedAt,String actor) { }
    public record Status(String storage,boolean owner,boolean api,List<Snapshot> snapshots) {
        public Status { snapshots=List.copyOf(snapshots); }
    }
    public record Capture(String table,String iri,int triples) { }
    public record Match(String source,String target,String sourceColumn,String targetColumn,String type,String sourceSnapshot,String targetSnapshot) { }
    public record Preview(String token,String schema,List<Snapshot> snapshots,List<OntologyRelations.Relation> candidates,int existing,Instant expires) {
        public Preview { snapshots=List.copyOf(snapshots);candidates=List.copyOf(candidates); }
    }
    public record Pending(Preview preview,Map<String,Ontology.Entry> entries) {
        public Pending { entries=Map.copyOf(entries); }
    }
    public record Saved(int saved,int skipped,List<String> tables) {
        public Saved { tables=List.copyOf(tables); }
    }
    public static List<String> names(List<String> values,int minimum) {
        if(values==null||values.size()<minimum||values.size()>MAX_OBJECTS||new HashSet<>(values).size()!=values.size())throw new Ontology.Failure(400,"native.scope");
        values.forEach(Ontology::name);return values.stream().sorted().toList();
    }
    public static void snapshot(String iri) {
        if(iri==null||!iri.matches("urn:dbc:capture:[a-f0-9]{32}"))throw new Ontology.Failure(409,"native.mismatch");
    }
    /** Catalog displays length/precision; native RDF keeps DATA_TYPE separately. */
    public static String baseType(String value){return value.replaceAll("\\([^)]*\\)","").strip();}
    public static final class State {
        private Pending pending;
        public synchronized void prepare(Pending value){pending=value;}
        public synchronized void clear(){pending=null;}
        public synchronized Pending consume(String schema,String token,boolean confirmed,Instant now){
            if(!confirmed)throw new Ontology.Failure(400,"confirmRequired");
            var value=pending;
            if(value==null||!value.preview().schema().equals(schema)||!value.preview().token().equals(token)||!now.isBefore(value.preview().expires()))throw new Ontology.Failure(409,"stale");
            pending=null;return value;
        }
    }
}
