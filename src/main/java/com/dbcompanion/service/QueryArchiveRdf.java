package com.dbcompanion.service;

import com.dbcompanion.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.*;
import org.eclipse.rdf4j.rio.helpers.NTriplesUtil;
import tools.jackson.databind.json.JsonMapper;

/** Reuses the exact filtered evidence used by the query screen. Does not infer business facts. */
public final class QueryArchiveRdf {
    private QueryArchiveRdf(){}
    public record Row(String subject,String predicate,String object){}
    private static final String DBC="urn:dbcompanion:ontology:";
    public static Model parse(String turtle){
        if(turtle==null||turtle.length()>QueryArchive.MAX_JSON)throw new Ontology.Failure(413,"archive.limit");
        try{var model=Rio.parse(new StringReader(turtle),"",RDFFormat.TURTLE);checked(model);return model;}
        catch(IOException|RuntimeException ex){if(ex instanceof Ontology.Failure f)throw f;throw new Ontology.Failure(409,"archive.invalidRdf");}
    }
    public static Model evidence(OntologyInquiry.Search search,List<Ontology.Entry> entries,JsonMapper json){
        var context=json.readTree(OntologyInquiry.payload(search,entries,json));var model=new LinkedHashModel();
        for(var item:context.path("rdf"))model.addAll(parse(item.path("rdf").asString()));
        var roots=new HashMap<String,String>();entries.forEach(e->roots.put(e.document().source().table(),OntologyRdf.export(e).tableIri()));
        var vf=SimpleValueFactory.getInstance();
        for(var e:search.evidence())if(e.usable()&&e.kind().equals("RELATION")){
            String key="urn:uuid:"+QueryArchive.id(search.id())+"/evidence/"+OntologyRdf.part(e.id());
            var relation=vf.createIRI(key);model.add(relation,vf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),vf.createIRI(DBC+"SelectedColumnMapping"));
            model.add(relation,vf.createIRI(DBC+"sourceTable"),vf.createIRI(Objects.requireNonNull(roots.get(e.source()))));
            model.add(relation,vf.createIRI(DBC+"targetTable"),vf.createIRI(Objects.requireNonNull(roots.get(e.target()))));
            model.add(relation,vf.createIRI(DBC+"evidenceId"),vf.createLiteral(e.id()));
            model.add(relation,vf.createIRI(DBC+"state"),vf.createLiteral(e.status()));
            e.basis().forEach(b->model.add(relation,vf.createIRI(DBC+"evidence"),vf.createLiteral(b)));
            if(!e.description().isBlank())model.add(relation,vf.createIRI("http://www.w3.org/2000/01/rdf-schema#comment"),vf.createLiteral(e.description()));
            if(e.from().size()!=e.to().size()||e.from().isEmpty())throw new Ontology.Failure(409,"archive.invalidRdf");
            for(int i=0;i<e.from().size();i++){
                var pair=vf.createIRI(key+"/position/"+(i+1));model.add(relation,vf.createIRI(DBC+"columnMapping"),pair);
                model.add(pair,vf.createIRI(DBC+"sourceColumn"),vf.createIRI(roots.get(e.source())+"/column/"+OntologyRdf.part(e.from().get(i))));
                model.add(pair,vf.createIRI(DBC+"targetColumn"),vf.createIRI(roots.get(e.target())+"/column/"+OntologyRdf.part(e.to().get(i))));
            }
        }
        checked(model);return model;
    }
    private static String term(Value v){String value=NTriplesUtil.toNTriplesString(v);if(value.getBytes(StandardCharsets.UTF_8).length>4000)throw new Ontology.Failure(413,"archive.termLimit");return value;}
    public static List<Row> rows(Model model){checkedSize(model);return model.stream().map(s->{if(!(s.getSubject() instanceof IRI)||s.getObject() instanceof BNode||s.getContext()!=null)throw new Ontology.Failure(409,"archive.invalidRdf");return new Row(term(s.getSubject()),term(s.getPredicate()),term(s.getObject()));}).toList();}
    private static void checkedSize(Model model){if(model.isEmpty()||model.size()>QueryArchive.MAX_TRIPLES)throw new Ontology.Failure(413,"archive.limit");}
    private static void checked(Model model){String value=textRows(rows(model));if(value.length()>QueryArchive.MAX_JSON)throw new Ontology.Failure(413,"archive.limit");}
    private static String textRows(List<Row> rows){return String.join("\n",rows.stream().map(r->r.subject()+" "+r.predicate()+" "+r.object()+" .").sorted().toList())+"\n";}
    public static String text(Model model){String value=textRows(rows(model));if(value.length()>QueryArchive.MAX_JSON)throw new Ontology.Failure(413,"archive.limit");return value;}
    public static String hash(Model model){return OntologyQueryService.hash(text(model));}
    public static Model fromRows(List<Row> rows){if(rows.size()>QueryArchive.MAX_TRIPLES)throw new Ontology.Failure(413,"archive.limit");return parse(textRows(rows));}
    public static void same(Model expected,Model actual){if(!expected.equals(actual))throw new Ontology.Failure(409,"archive.verifyFailed");}
}
