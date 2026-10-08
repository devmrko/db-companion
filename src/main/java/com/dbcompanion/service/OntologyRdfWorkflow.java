package com.dbcompanion.service;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import java.util.*;

/** Human-readable evidence and a closed, server-validated handoff to the existing SQL workflow. */
public final class OntologyRdfWorkflow {
    private OntologyRdfWorkflow(){}
    public record Column(String name,String type,String description){}
    public record Source(String name,String concept,String description,String state,int revision,boolean recommended,List<Column> columns,String schema,String versionIri){}
    public record Rule(String term,String definition,String criteria){}
    public record Relation(String id,String source,String target,List<String> from,List<String> to,String label,String condition,String status,boolean usable,String origin,List<String> evidence,String resourceIri){}
    public record TermReference(String id,long revision,String term,List<String> sources){}
    public record Summary(List<Source> sources,List<Rule> rules,List<Relation> relations,List<TermReference> termReferences){}
    /** Display-only mapping from parsed dictionary SELECT criteria, not prose or an inferred join. */
    private static TermReference termReference(BusinessGlossary.Term term,String schema,Set<String> names){
        var parsed=SelectAiSqlReferences.analyze(SelectAiTest.Action.SQL,term.criteria());
        var sources=parsed.status().equals("PARSED")?parsed.tables().stream()
                .filter(t->t.owner()==null||t.owner().equals(schema)).map(SelectAiSqlReferences.Table::name)
                .filter(names::contains).distinct().toList():List.<String>of();
        return new TermReference(term.id(),term.revision(),term.term(),sources);
    }
    private static String relationIri(OntologyRelations.Relation relation,Entry source){
        String version="urn:uuid:"+source.documentId()+"/revision/"+source.revision();
        if(relation.review()!=null)return version+"/relationship/"+relation.review().id();
        if(relation.key()!=null)return version+"/table/key/"+OntologyRdf.part(relation.key().name());
        return ""; // Derived candidates are not falsely presented as stored RDF resources.
    }
    public static Set<String> preferred(OntologyInquiry.Dataset data,OntologyQuestionGrounding.Result ground){
        if(ground==null)return Set.of();var names=new LinkedHashSet<String>();
        for(var e:data.entries()){String name=e.document().source().table();
            if(java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_$#])"+java.util.regex.Pattern.quote(name)+"(?![\\p{L}\\p{N}_$#])",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(ground.interpreted()).find())names.add(name);}
        return Set.copyOf(names);
    }
    public static Summary summary(OntologyInquiry.Dataset data,OntologyRdfSearch.Result rdf,OntologyQuestionGrounding.Result ground){
        var preferred=preferred(data,ground);var indexed=new HashMap<String,Entry>();data.entries().forEach(e->indexed.put(e.document().source().table(),e));
        var sources=rdf.hits().stream().map(h->{var e=indexed.get(h.table());var m=e.document().meaning();
            return new Source(h.table(),m.concept(),m.description(),h.state(),h.revision(),preferred.contains(h.table()),e.document().source().columns().stream().filter(c->OntologyContext.safe(c,m.columns().get(c.name()))&&ReviewedSql.scalar(c.dataType())).map(c->new Column(c.name(),c.dataType(),m.columns().get(c.name()).description())).toList(),data.schema(),h.versionIri());}).toList();
        var names=sources.stream().map(Source::name).collect(java.util.stream.Collectors.toSet());
        var relations=data.analysis().relations().stream().filter(r->r.targetSchema().equals(data.schema())&&names.contains(r.source())&&names.contains(r.target())).map(r->new Relation(r.id(),r.source(),r.target(),r.sourceColumns(),r.targetColumns(),r.label(),r.condition(),r.status(),OntologyInquiry.confirmed(r)&&r.condition().isBlank()&&OntologyInquiry.safeMapping(indexed.get(r.source()),r.sourceColumns())&&OntologyInquiry.safeMapping(indexed.get(r.target()),r.targetColumns()),r.origin(),r.evidence(),relationIri(r,indexed.get(r.source())))).toList();
        var rules=ground==null?List.<Rule>of():ground.terms().stream().map(t->new Rule(t.term(),t.definition(),t.criteria())).toList();
        var references=ground==null?List.<TermReference>of():ground.terms().stream().map(t->termReference(t,data.schema(),names)).toList();
        return new Summary(sources,rules,relations,references);
    }
    public static OntologyInquiry.Search select(OntologyInquiry.Dataset data,OntologyQueryService.GroundedSearch ground,List<String> tables,List<String> ids,String mode){
        if(ground==null||ground.rdf()==null||tables==null||tables.isEmpty()||tables.size()>10||new HashSet<>(tables).size()!=tables.size()||ids==null||new HashSet<>(ids).size()!=ids.size()||!Set.of("INDEPENDENT","JOIN").contains(mode))throw new Failure(400,"query.invalid");
        var summary=summary(data,ground.rdf(),ground.interpretation());
        if(!summary.sources().stream().map(Source::name).toList().containsAll(tables))throw new Failure(400,"query.invalid");
        if(mode.equals("INDEPENDENT")&&!ids.isEmpty())throw new Failure(400,"query.invalid");
        var allowed=summary.relations().stream().filter(Relation::usable).filter(r->tables.contains(r.source())&&tables.contains(r.target())).map(Relation::id).toList();
        if(!allowed.containsAll(ids))throw new Failure(400,"query.noEvidence");
        var relations=data.analysis().relations().stream().filter(r->ids.contains(r.id())).toList();
        if(mode.equals("JOIN")){
            var connected=new HashSet<String>();connected.add(tables.getFirst());
            for(int i=0;i<tables.size();i++)for(var r:relations)if(connected.contains(r.source())||connected.contains(r.target())){connected.add(r.source());connected.add(r.target());}
            if(tables.size()<2||!connected.containsAll(tables))throw new Failure(400,"query.noEvidence");
        }
        var base=OntologyInquiry.evidence(data,ground.search().question(),"",List.of(),new TreeSet<>(tables),relations);
        var evidence=base.evidence().stream().filter(OntologyInquiry.Evidence::usable).toList();
        String id=mode.equals("JOIN")?"RDF_JOIN":"INDEPENDENT";
        var route=new OntologyInquiry.Route(id,List.copyOf(tables),evidence.stream().filter(e->e.kind().equals("RELATION")).map(OntologyInquiry.Evidence::id).toList(),evidence.stream().map(OntologyInquiry.Evidence::id).toList(),0);
        return new OntologyInquiry.Search(base.id(),base.schema(),base.question(),"",base.matches(),evidence,base.references(),base.checkedAt(),List.of(),List.of(route),List.of(),false);
    }
}
