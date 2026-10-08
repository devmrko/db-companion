package com.dbcompanion.service;

import com.dbcompanion.model.*;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Recommendation context is a shortlist, not an export of the ontology. */
public final class OntologyPlanContext {
    private OntologyPlanContext(){}
    public static String payload(OntologyInquiry.Dataset data,OntologyInquiry.Search search,
            OntologyQuestionGrounding.Result grounding,List<OntologyRelations.Relation> candidates,JsonMapper json){
        var terms=grounding==null?List.<BusinessGlossary.Term>of():grounding.terms();
        var names=new LinkedHashSet<String>();
        String explicit=search.question()+"\n"+terms.stream().map(t->t.definition()+"\n"+t.criteria()).reduce("",(a,b)->a+"\n"+b);
        // Match complete known identifiers only. This extracts references, never executes dictionary SQL.
        data.entries().forEach(e->{String name=e.document().source().table();
            if(Pattern.compile("(?<![\\p{L}\\p{N}_$#])"+Pattern.quote(name)+"(?![\\p{L}\\p{N}_$#])",Pattern.CASE_INSENSITIVE).matcher(explicit).find())names.add(name);});
        if(!search.anchor().isBlank())names.add(search.anchor());
        if(names.isEmpty())search.concepts().stream().flatMap(c->c.targets().stream()).sorted(Comparator.comparingInt(OntologyInquiry.Target::score).reversed()).map(OntologyInquiry.Target::table).limit(8).forEach(names::add);
        if(names.isEmpty())search.matches().stream().map(OntologyInquiry.Match::table).limit(8).forEach(names::add);
        var routes=search.routes().stream().filter(r->r.tables().stream().anyMatch(names::contains)).limit(5).toList();
        routes.forEach(r->names.addAll(r.tables()));
        var sources=data.entries().stream().filter(e->names.contains(e.document().source().table())).map(e->Map.of("name",e.document().source().table(),"state",e.state(),"concept",e.document().meaning().concept())).toList();
        var payload=new LinkedHashMap<String,Object>();
        payload.put("originalQuestion",search.question());
        payload.put("dictionaryEvidence",terms.stream().map(t->Map.of("id",t.id(),"revision",t.revision(),"term",t.term(),"definition",t.definition(),"criteria",t.criteria())).toList());
        payload.put("availableSources",sources);
        // Route evidence is retained once; reference histories and capture metadata are not needed here.
        payload.put("routes",routes);
        var evidenceIds=new HashSet<String>();routes.forEach(r->evidenceIds.addAll(r.evidence()));
        payload.put("evidence",search.evidence().stream().filter(e->evidenceIds.contains(e.id())).map(e->Map.of("id",e.id(),"source",e.source(),"target",e.target(),"from",e.from(),"to",e.to(),"description",e.description(),"status",e.status(),"usable",e.usable())).toList());
        var selected=new ArrayList<Object>();payload.put("candidateRelations",selected);
        int omitted=0;
        for(var r:candidates){
            if(!names.contains(r.source())||!names.contains(r.target())||selected.size()>=12){omitted++;continue;}
            selected.add(Map.of("id",r.id(),"source",r.source(),"targetSchema",r.targetSchema(),"target",r.target(),"sourceColumns",r.sourceColumns(),"targetColumns",r.targetColumns(),"status",r.status(),"label",r.label(),"condition",r.condition(),"evidence",r.evidence()));
            // Omit whole optional candidates, never truncate conditions or uncertainty text.
            if(json.writeValueAsString(payload).length()>48_000){selected.removeLast();omitted++;}
        }
        payload.put("selection",Map.of("omittedCandidates",omitted,"omittedSources",data.entries().size()-sources.size(),"policy","Shortlisted context only. Omitted candidates are not rejected or approved. If evidence is insufficient, return REVIEW; never invent a join."));
        return json.writeValueAsString(payload);
    }
}
