package com.dbcompanion.service;

import com.dbcompanion.model.BusinessGlossary;
import java.util.ArrayList;
import java.util.List;

/** Two display/export graphs. User declarations are not approvals or executable join rules. */
public final class BusinessGlossaryRdf {
    private BusinessGlossaryRdf() {}
    public record View(String meaning,String mapping,List<OntologyRdf.Triple> triples,List<String> sources) {}
    private static final String NS="urn:dbcompanion:glossary:";
    private static final String SKOS="http://www.w3.org/2004/02/skos/core#";
    public static View export(BusinessGlossary.Term term){
        BusinessGlossary.id(term.id());if(term.revision()<1)throw BusinessGlossary.invalid();
        String root="urn:uuid:"+term.id()+"/revision/"+term.revision();
        var meaning=new StringBuilder();var mapping=new StringBuilder();var triples=new ArrayList<OntologyRdf.Triple>();var sources=new ArrayList<String>();
        iri(meaning,triples,root,"http://www.w3.org/1999/02/22-rdf-syntax-ns#type",SKOS+"Concept");
        value(meaning,triples,root,SKOS+"prefLabel",term.term());
        term.aliases().forEach(alias->value(meaning,triples,root,SKOS+"altLabel",alias));
        value(meaning,triples,root,SKOS+"definition",term.definition());
        value(meaning,triples,root,NS+"state",term.enabled()?"ACTIVE_USER_DECLARED":"INACTIVE_USER_DECLARED");
        value(meaning,triples,root,NS+"revision",Long.toString(term.revision()));
        value(meaning,triples,root,NS+"sqlCriteriaReference",term.criteria());
        if(term.rdf()!=null){
            var rdf=term.rdf();value(meaning,triples,root,NS+"domain",rdf.domain());value(meaning,triples,root,NS+"unit",rdf.unit());
            value(meaning,triples,root,NS+"scope",rdf.scope());value(meaning,triples,root,NS+"grain",rdf.grain());
            rdf.rules().forEach(rule->value(meaning,triples,root,NS+"ruleNote",rule));rdf.citations().forEach(citation->value(meaning,triples,root,NS+"citation",citation));
            int position=0;for(var item:rdf.mappings()){
                String id=root+"/mapping/"+(++position),object="urn:dbcompanion:source:"+OntologyRdf.part(item.schema())+"/"+OntologyRdf.part(item.table());
                iri(mapping,triples,root,NS+"sourceMapping",id);iri(mapping,triples,id,NS+"source",object);
                iri(mapping,triples,id,NS+"column",object+"/column/"+OntologyRdf.part(item.column()));
                value(mapping,triples,id,NS+"schema",item.schema());value(mapping,triples,id,NS+"table",item.table());value(mapping,triples,id,NS+"columnName",item.column());
                value(mapping,triples,id,NS+"role",item.role());value(mapping,triples,id,NS+"aggregation",item.aggregation());
                value(mapping,triples,id,NS+"origin","USER_DECLARED_NOT_JOIN_APPROVAL");
                sources.add(item.schema()+"."+item.table()+"."+item.column()+" · "+item.role()+" · "+item.aggregation());
            }
        }
        return new View(meaning.toString(),mapping.toString(),List.copyOf(triples),List.copyOf(sources));
    }
    private static void value(StringBuilder text,List<OntologyRdf.Triple> triples,String subject,String predicate,String value){
        if(value==null||value.isBlank())return;
        text.append('<').append(subject).append("> <").append(predicate).append("> ").append(OntologyRdf.literal(value)).append(" .\n");
        triples.add(new OntologyRdf.Triple(subject,predicate,new OntologyRdf.Term("LITERAL",value)));
    }
    private static void iri(StringBuilder text,List<OntologyRdf.Triple> triples,String subject,String predicate,String object){
        text.append('<').append(subject).append("> <").append(predicate).append("> <").append(object).append("> .\n");
        triples.add(new OntologyRdf.Triple(subject,predicate,new OntologyRdf.Term("IRI",object)));
    }
}
