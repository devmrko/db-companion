package com.dbcompanion.service;

import com.dbcompanion.model.*;
import java.util.*;

/** Read-only retrieval of typed RDF exported from the current saved ontology revisions. */
public final class OntologyRdfSearch {
    private OntologyRdfSearch(){}
    public record Hit(String table,String state,int revision,String versionIri,List<String> terms,List<OntologyRdf.Triple> triples,boolean limited){}
    public record Result(String question,List<String> terms,List<Hit> hits,boolean limited){}
    private static final Set<String> PREDICATES=Set.of("name","businessConcept","sourceComment","comment","label","prefLabel","altLabel","definition","alias","usageGuidance","valueMeaning","conditionNote","targetTable");
    private static String local(String iri){return iri.substring(Math.max(iri.lastIndexOf('#'),iri.lastIndexOf(':'))+1);}
    public static Result search(OntologyInquiry.Dataset data,String question,List<String> terms,String anchor){
        return search(data,question,terms,anchor,Set.of());
    }
    public static Result search(OntologyInquiry.Dataset data,String question,List<String> terms,String anchor,Set<String> preferred){
        if(question==null||question.isBlank()||question.length()>2000||terms==null||terms.isEmpty()||terms.size()>12||terms.stream().anyMatch(t->t==null||t.isBlank()||t.length()>80))throw new Ontology.Failure(400,"query.invalid");
        var found=new ArrayList<Hit>();int chars=0;boolean limited=false;
        var ordered=data.entries().stream().sorted(Comparator.comparingInt((Ontology.Entry e)->preferred.contains(e.document().source().table())?1000:(int)terms.stream().filter(t->BusinessGlossary.mentions(e.document().meaning().concept(),t)||BusinessGlossary.mentions(e.document().source().table(),t)).count()*10).reversed().thenComparing(e->e.document().source().table())).toList();
        for(var entry:ordered){
            var source=entry.document().source();if(anchor!=null&&!anchor.isBlank()&&!source.table().equals(anchor))continue;
            var graph=OntologyRdf.export(entry);
            var excluded=source.columns().stream().filter(c->!OntologyContext.safe(c,entry.document().meaning().columns().get(c.name()))).map(c->graph.tableIri()+"/column/"+OntologyRdf.part(c.name())).toList();
            var safe=graph.triples().stream().filter(t->excluded.stream().noneMatch(p->t.subject().equals(p)||t.subject().startsWith(p+"/")||t.object().kind().equals("IRI")&&(t.object().value().equals(p)||t.object().value().startsWith(p+"/")))).toList();
            var subjects=new LinkedHashSet<String>();var matched=new LinkedHashSet<String>();
            if(preferred.contains(source.table())){subjects.add(graph.tableIri());matched.add(source.table());}
            for(var triple:safe){if(!triple.object().kind().equals("LITERAL")||!PREDICATES.contains(local(triple.predicate())))continue;
                for(String term:terms)if(BusinessGlossary.mentions(triple.object().value(),term)){
                    subjects.add(triple.subject());matched.add(term);
                    if(triple.subject().endsWith("/concept"))subjects.add(triple.subject().substring(0,triple.subject().length()-8));
                }
            }
            if(subjects.isEmpty())continue;
            if(found.size()>=10){limited=true;continue;}
            // Include outgoing mappings and one-hop resources (columns, concepts, relation key pairs).
            var expanded=new HashSet<>(subjects);safe.stream().filter(t->subjects.contains(t.subject())&&t.object().kind().equals("IRI")).forEach(t->expanded.add(t.object().value()));
            var triples=new ArrayList<OntologyRdf.Triple>();boolean clipped=false;
            for(var triple:safe){if(!expanded.contains(triple.subject()))continue;
                int size=triple.subject().length()+triple.predicate().length()+triple.object().value().length();
                if(triples.size()>=100||chars+size>100_000){clipped=true;continue;}
                triples.add(triple);chars+=size;
            }
            limited|=clipped;found.add(new Hit(source.table(),entry.state(),entry.revision(),graph.versionIri(),List.copyOf(matched),List.copyOf(triples),clipped));
        }
        return new Result(question,List.copyOf(terms),List.copyOf(found),limited);
    }
}
