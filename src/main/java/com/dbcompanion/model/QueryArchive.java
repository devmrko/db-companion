package com.dbcompanion.model;

import com.dbcompanion.service.OntologyInquiry;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class QueryArchive {
    private QueryArchive(){}
    public static final String TYPE="ONTOLOGY_QUERY", STORE="RDF_STORE";
    public static final String STORE_ID="f379292b-af43-423d-81be-cd9a3c4ef53a";
    public static final int MAX_JSON=1_000_000, MAX_TRIPLES=10_000;
    public record Status(String records,String rdf,boolean owner,boolean api,String tablespace,String checkedAt,String diagnostic){}
    public record Locator(String database,String service,String schema,String network,String model,String modelId,String namedGraph){}
    public record Preview(String id,String token,String schema,String question,String sparql,String turtle,
                          JsonNode payload,int triples,Instant expires){}
    public record Pending(Preview preview,OntologyInquiry.Search search,List<Ontology.Entry> entries){public Pending{entries=List.copyOf(entries);}}
    public record Item(String seq,String id,String state,String actor,String recordedAt,String updatedAt,String question){}
    public record Page(List<Item> rows,String next){public Page{rows=List.copyOf(rows);}}
    public record Detail(Item item,JsonNode payload){}
    public record Graph(String id,int count,String hash,String turtle){}
    public static String id(String value){
        try{if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();return value;}
        catch(RuntimeException ex){throw new Ontology.Failure(400,"archive.invalid");}
    }
    public static String graph(String id,boolean result){return "urn:uuid:"+id(id)+(result?"/result":"/source");}
    public static void owner(String schema,String login){Ontology.name(schema);if(!schema.equals(login))throw new Ontology.Failure(403,"ownerRequired");}
    public static final class State {
        private final Map<String,Status> states=new HashMap<>();private Pending pending;
        public synchronized Status status(String schema,boolean refresh,java.util.function.Supplier<Status> loader){if(refresh)states.remove(schema);return states.computeIfAbsent(schema,k->loader.get());}
        public synchronized void forget(String schema){states.remove(schema);pending=null;}
        public synchronized void clear(){states.clear();pending=null;}
        public synchronized void prepare(Pending value){pending=value;}
        public synchronized Pending consume(String token,String schema,boolean confirmed,Instant now){
            if(!confirmed)throw new Ontology.Failure(400,"archive.confirm");
            if(pending==null||!pending.preview().token().equals(token)||!pending.preview().schema().equals(schema)||!now.isBefore(pending.preview().expires()))throw new Ontology.Failure(409,"query.stale");
            var value=pending;pending=null;return value;
        }
    }
}
