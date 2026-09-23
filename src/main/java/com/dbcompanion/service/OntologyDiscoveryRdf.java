package com.dbcompanion.service;

import java.util.*;

/** Lossless typed-RDF transport (apart from the same actor/profile omissions as compact Turtle).
 * A shared literal is referenced by text index; predicates and ordered key-position triples remain distinct. */
public final class OntologyDiscoveryRdf {
    private OntologyDiscoveryRdf(){}
    public static boolean included(OntologyRdf.Triple t){return !Set.of("urn:dbcompanion:ontology:actor","urn:dbcompanion:ontology:profile").contains(t.predicate());}
    public static Map<String,Object> encode(OntologyRdf.Export rdf){
        var rows=rdf.triples().stream().filter(OntologyDiscoveryRdf::included).toList();var counts=new HashMap<String,Integer>();
        rows.stream().filter(t->t.object().kind().equals("LITERAL")).forEach(t->counts.merge(t.object().value(),1,Integer::sum));
        var texts=new LinkedHashMap<String,Integer>();
        for(var t:rows)if(t.object().kind().equals("LITERAL")&&t.object().value().length()>=80&&counts.get(t.object().value())>1)texts.computeIfAbsent(t.object().value(),v->texts.size());
        var nodes=new LinkedHashMap<String,Map<String,List<Object>>>();
        for(var t:rows){
            var properties=nodes.computeIfAbsent(shortIri(t.subject(),rdf),key->new LinkedHashMap<>());var term=t.object();
            Object value=switch(term.kind()){
                case "IRI"->Map.of("iri",shortIri(term.value(),rdf));
                case "INTEGER"->Map.of("integer",term.value());
                default->texts.containsKey(term.value())?Map.of("text",texts.get(term.value())):term.value();
            };
            properties.computeIfAbsent(shortIri(t.predicate(),rdf),key->new ArrayList<>()).add(value);
        }
        var result=new LinkedHashMap<String,Object>();result.put("base",rdf.versionIri());result.put("prefixes",rdf.prefixes());result.put("texts",List.copyOf(texts.keySet()));result.put("nodes",nodes);return result;
    }
    private static String shortIri(String iri,OntologyRdf.Export rdf){
        if(iri.equals(rdf.versionIri()))return "@";
        if(iri.startsWith(rdf.versionIri()+"/"))return "@"+iri.substring(rdf.versionIri().length());
        for(var p:rdf.prefixes().entrySet())if(iri.startsWith(p.getValue()))return p.getKey()+":"+iri.substring(p.getValue().length());
        return iri;
    }
}
