package com.dbcompanion.service;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyRelations.Relation;
import com.dbcompanion.service.OntologyInquiry.*;
import java.util.*;

/** Bounded, deterministic retrieval over stored metadata. Scores are not confidence probabilities. */
public final class OntologyPaths {
    private OntologyPaths() {}
    public static final int MAX_HOPS=3, MAX_STEPS=20_000, MAX_ROUTES=5, MAX_TERMS=4;
    private static final Set<String> STOP=Set.of("어떻게","무엇","어떤","관계","연결","설명","알려줘","보여줘","해줘","있어","있는","되나요","속하는가","조회","정보","what","which","how","are","the","and","does","with","from","show","list","relationship","relationships","between");
    private record Score(int value,String basis) {}
    private record Candidate(List<String> tables,List<Relation> edges,int score) {}
    private record Indexed(Entry entry,Set<String> ownKeys,Set<String> foreignKeys,Set<String> valueTerms) {}

    private static String normal(String s){return OntologyRelations.lexical(Objects.toString(s,""));}
    private static List<String> tokens(String question){
        var out=new LinkedHashSet<String>();
        for(String word:question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_$#]+")){
            String term=word.replaceFirst("(?<=[가-힣])(?:에서는|에서|으로|와|과|은|는|을|를|의|에)$","");
            if(term.length()>=2&&!STOP.contains(term))out.add(term);
        }
        return List.copyOf(out);
    }
    private static Score match(String field,String term,int exact,int partial,String basis){
        String f=normal(field),q=normal(term);
        return f.isEmpty()||!f.contains(q)?new Score(0,""):new Score(f.equals(q)?exact:partial,basis);
    }
    private static Score best(Score a,Score b){return b.value()>a.value()?b:a;}
    private static Score columnScore(ColumnInfo c,ColumnMeaning meaning,String term,int rank,String basis){
        Score out=match(c.name(),term,rank,rank-5,basis);
        if(meaning.definition()!=null){
            out=best(out,match(meaning.definition().label(),term,rank+5,rank,basis));
            for(String alias:meaning.definition().aliases())out=best(out,match(alias,term,rank+5,rank,"ALIAS"));
        }
        out=best(out,match(c.comment(),term,rank,rank-10,basis));
        return best(out,match(meaning.description(),term,rank,rank-10,basis));
    }
    private static Score score(Indexed table,String term){
        var e=table.entry();var s=e.document().source();var m=e.document().meaning();
        Score out=match(s.table(),term,120,40,"TABLE_NAME");
        out=best(out,match(m.concept(),term,105,65,"CONCEPT"));
        out=best(out,match(s.comment(),term,95,50,"TABLE_COMMENT"));
        out=best(out,match(m.description(),term,65,25,"DESCRIPTION"));
        if(table.valueTerms().contains(normal(term)))out=best(out,new Score(130,"VALUE_TERM"));
        for(var c:s.columns()){
            var meaning=m.columns().get(c.name());if(!OntologyContext.safe(c,meaning))continue;
            boolean foreign=table.foreignKeys().contains(c.name()),key=table.ownKeys().contains(c.name())&&!foreign;
            int rank=key?85:foreign?25:45;String basis=key?"KEY_TERM":foreign?"REFERENCE_TERM":"COLUMN_TERM";
            out=best(out,columnScore(c,meaning,term,rank,basis));
        }
        return out;
    }
    private static Score referringScore(Indexed table,String term,Score raw,Map<String,Score> scores,List<Relation> outgoing){
        // Exact entity identities win. A surrogate PK description must not turn a referring
        // table into the referenced entity when the mapped column and target identify that term.
        // Broad table descriptions can name the referenced entity even when FK columns use
        // different terminology. The confirmed mapping, not a guessed synonym, connects them.
        if(raw.value()==0||raw.value()>=95)return raw;
        var source=table.entry().document().source();var meaning=table.entry().document().meaning();
        for(var relation:outgoing){
            if(relation.target().equals(source.table())||scores.get(relation.target()).value()<50)continue;
            boolean broadDescription=Set.of("TABLE_COMMENT","CONCEPT","DESCRIPTION").contains(raw.basis());
            boolean refers=broadDescription||source.columns().stream().filter(c->relation.sourceColumns().contains(c.name()))
                .anyMatch(c->columnScore(c,meaning.columns().get(c.name()),term,25,"REFERENCE_TERM").value()>0);
            if(refers)return new Score(Math.min(raw.value(),30),"REFERENCE_TERM");
        }
        return raw;
    }
    private static void phrase(Set<String> words,String value,String question){
        String term=Objects.toString(value,"").strip();if(term.length()>=2&&term.length()<=80&&!STOP.contains(term)&&normal(question).contains(normal(term)))words.add(term);
    }
    public static Search search(Dataset data,String question,String anchor){
        var known=new LinkedHashMap<String,Entry>();var indexed=new ArrayList<Indexed>();var words=new LinkedHashSet<>(tokens(question));
        for(var e:data.entries()){
            var s=e.document().source();var m=e.document().meaning();known.put(s.table(),e);
            var keys=new HashSet<String>();var foreign=new HashSet<String>();
            for(var key:s.keys()){
                if(key.type().equals("R"))foreign.addAll(key.columns());
                else if(Set.of("P","U").contains(key.type())&&key.status().equals("ENABLED")&&key.validated().equals("VALIDATED"))keys.addAll(key.columns());
            }
            var valueTerms=new HashSet<String>();
            if("APPROVED".equals(e.state()))for(var binding:m.valueMappings())for(String value:OntologyValues.terms(binding))
                if(OntologyValues.mentions(question,value)){valueTerms.add(normal(value));phrase(words,value,question);}
            indexed.add(new Indexed(e,keys,foreign,valueTerms));phrase(words,s.table(),question);phrase(words,m.concept(),question);
            for(var c:s.columns()){var cm=m.columns().get(c.name());if(OntologyContext.safe(c,cm)&&cm.definition()!=null){phrase(words,cm.definition().label(),question);cm.definition().aliases().forEach(a->phrase(words,a,question));}}
        }
        // Preserve the longest saved phrase, rather than treating each of its words as a separate intent.
        var uniqueWords=new LinkedHashMap<String,String>();words.forEach(w->uniqueWords.putIfAbsent(normal(w),w));
        var terms=uniqueWords.values().stream().filter(w->uniqueWords.values().stream().noneMatch(v->!normal(v).equals(normal(w))&&normal(v).contains(normal(w))))
            .sorted(Comparator.comparingInt((String w)->normal(question).indexOf(normal(w))).thenComparing(Comparator.naturalOrder())).toList();
        if(terms.size()>80)throw new Failure(413,"query.narrow");
        var edges=data.analysis().relations().stream().filter(r->r.targetSchema().equals(data.schema())&&known.containsKey(r.source())&&known.containsKey(r.target()))
            .filter(r->OntologyInquiry.confirmed(r)&&r.condition().isBlank())
            .filter(r->OntologyInquiry.safeMapping(known.get(r.source()),r.sourceColumns())&&OntologyInquiry.safeMapping(known.get(r.target()),r.targetColumns()))
            .sorted(Comparator.comparing(Relation::id)).toList();
        var outgoing=new HashMap<String,List<Relation>>();edges.forEach(r->outgoing.computeIfAbsent(r.source(),k->new ArrayList<>()).add(r));
        var concepts=new ArrayList<Concept>();boolean limited=false;
        for(String term:terms){
            var scores=new HashMap<String,Score>();indexed.forEach(e->scores.put(e.entry().document().source().table(),score(e,term)));
            var hits=new ArrayList<Target>();for(var e:indexed){String name=e.entry().document().source().table();
                var value=referringScore(e,term,scores.get(name),scores,outgoing.getOrDefault(name,List.of()));if(value.value()>0)hits.add(new Target(name,value.value(),value.basis()));}
            hits.sort(Comparator.comparingInt(Target::score).reversed().thenComparing(Target::table));
            if(hits.isEmpty())continue;int threshold=hits.getFirst().score()-8;var near=hits.stream().filter(t->t.score()>=threshold).toList();
            limited|=near.size()>3;concepts.add(new Concept(term,near.stream().limit(3).toList()));
        }
        if(concepts.size()>MAX_TERMS)throw new Failure(413,"query.narrow");
        var walk=new Walk(concepts,edges,anchor);walk.run();limited|=walk.limited;
        var candidates=walk.found.values().stream().sorted(Comparator.comparingInt(Candidate::score).reversed().thenComparing(c->String.join("\u0000",c.tables()))
            .thenComparing(c->c.edges().stream().map(Relation::id).sorted().toList().toString())).toList();
        var chosen=new ArrayList<Candidate>();var names=new LinkedHashSet<String>();var mappings=new LinkedHashMap<String,Relation>();
        for(var candidate:candidates){var next=new LinkedHashSet<>(names);next.addAll(candidate.tables());if(chosen.size()>=MAX_ROUTES||next.size()>OntologyInquiry.MAX_DOCUMENTS){limited=true;continue;}
            chosen.add(candidate);names=next;candidate.edges().forEach(r->mappings.put(r.id(),r));}
        var matches=new ArrayList<Match>();for(String table:names){var matched=concepts.stream().filter(c->c.targets().stream().anyMatch(t->t.table().equals(table))).map(Concept::term).toList();if(!matched.isEmpty())matches.add(new Match(table,matched));}
        var base=OntologyInquiry.evidence(data,question,anchor,matches,names,new ArrayList<>(mappings.values()));
        var relationIds=new HashMap<String,String>();int relationNumber=0;for(String id:mappings.keySet())relationIds.put(id,"R"+(++relationNumber));
        var routes=new ArrayList<Route>();for(var c:chosen){
            var ids=c.edges().stream().map(r->relationIds.get(r.id())).toList();var evidence=base.evidence().stream().filter(e->e.usable()&&(e.kind().equals("RELATION")?ids.contains(e.id()):c.tables().contains(e.source()))).map(Evidence::id).toList();
            routes.add(new Route("P"+(routes.size()+1),c.tables(),ids,evidence,c.score()));
        }
        var contexts=names.stream().map(known::get).map(OntologyPaths::context).toList();
        return new Search(base.id(),base.schema(),base.question(),base.anchor(),base.matches(),base.evidence(),base.references(),base.checkedAt(),concepts,routes,contexts,limited);
    }
    private static TableContext context(Entry e){
        var s=e.document().source();var m=e.document().meaning();var columns=new ArrayList<Field>();
        for(var c:s.columns()){var meaning=m.columns().get(c.name());if(!OntologyContext.safe(c,meaning))continue;
            var d=meaning.definition();columns.add(new Field(c.name(),c.dataType(),meaning.description(),d==null?"":d.label(),d==null?List.of():d.aliases()));}
        return new TableContext(s.table(),m.concept(),m.description(),e.state(),e.revision(),columns);
    }
    private static final class Walk {
        final List<Concept> concepts;final String anchor;final Map<String,List<Relation>> adjacency=new TreeMap<>();final Map<String,Candidate> found=new LinkedHashMap<>();int steps;boolean limited;
        Walk(List<Concept> concepts,List<Relation> edges,String anchor){this.concepts=concepts;this.anchor=anchor;for(var r:edges){adjacency.computeIfAbsent(r.source(),k->new ArrayList<>()).add(r);if(!r.source().equals(r.target()))adjacency.computeIfAbsent(r.target(),k->new ArrayList<>()).add(r);}}
        void run(){
            var starts=new TreeSet<String>();concepts.forEach(c->c.targets().forEach(t->starts.add(t.table())));if(!anchor.isEmpty())starts.add(anchor);
            for(String start:starts){visit(new ArrayList<>(List.of(start)),new ArrayList<>());if(limited)break;}
        }
        boolean covers(List<String> nodes){return (!concepts.isEmpty()||!anchor.isEmpty())&&concepts.stream().allMatch(c->c.targets().stream().anyMatch(t->nodes.contains(t.table())))&&(anchor.isEmpty()||nodes.contains(anchor));}
        void visit(ArrayList<String> nodes,ArrayList<Relation> edges){
            if(++steps>MAX_STEPS){limited=true;return;}
            if(covers(nodes)){
                int score=concepts.stream().mapToInt(c->c.targets().stream().filter(t->nodes.contains(t.table())).mapToInt(Target::score).max().orElse(0)).sum()-edges.size()*4;
                var order=new ArrayList<>(nodes);var reverse=new ArrayList<>(nodes);Collections.reverse(reverse);
                // Stable orientation follows the first question term when it identifies an endpoint.
                boolean flip=anchor.isEmpty()&&!concepts.isEmpty()&&concepts.getFirst().targets().stream().anyMatch(t->t.table().equals(nodes.getLast()))&&concepts.getFirst().targets().stream().noneMatch(t->t.table().equals(nodes.getFirst()));
                if(flip)order=reverse;var rels=new ArrayList<>(edges);if(flip)Collections.reverse(rels);
                String key=new TreeSet<>(nodes)+"|"+edges.stream().map(Relation::id).sorted().toList();found.putIfAbsent(key,new Candidate(List.copyOf(order),List.copyOf(rels),score));return;
            }
            if(edges.size()==MAX_HOPS)return;
            for(var r:adjacency.getOrDefault(nodes.getLast(),List.of())){
                String next=r.source().equals(nodes.getLast())?r.target():r.source();if(nodes.contains(next))continue;
                nodes.add(next);edges.add(r);visit(nodes,edges);edges.removeLast();nodes.removeLast();if(limited)return;
            }
        }
    }
}
