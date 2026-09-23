package com.dbcompanion.model;

import com.dbcompanion.service.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.*;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/** Saved metadata only. Separate from the ontology inquiry's consent and execution state. */
public final class SelectAiEvidence {
    private SelectAiEvidence() {}
    public record Snapshot(String schema,String question,String route,List<OntologyAnalysis.Reference> references,
                           String hash,String source,@JsonIgnore List<Ontology.Entry> entries) {
        public Snapshot { references=List.copyOf(references);entries=List.copyOf(entries); }
    }
    public static String key(Snapshot value){return value==null?"":value.hash();}
    public static boolean same(Snapshot a,Snapshot b){return key(a).equals(key(b));}
    public static AiAssistant.Failure stale(){return new AiAssistant.Failure(409,"aitest.evidenceStale","근거가 변경됐습니다. 근거를 다시 찾고 선택해 주세요.");}
    public static void scope(String schema,List<String> accessible){if(schema==null||!accessible.contains(schema))throw stale();}
    public static void verify(Snapshot saved,List<Ontology.Entry> current){
        if(saved==null)return;
        if(!PropertyGraph.sameDefinitions(saved.entries(),current)
                ||!saved.references().equals(current.stream().map(OntologyContext::reference).toList()))throw stale();
    }
    public static final class State {
        private OntologyInquiry.Dataset data;
        private OntologyInquiry.Search search;
        private Snapshot selected;
        public synchronized Snapshot selected(){return selected;}
        public synchronized void invalidate(){search=null;selected=null;}
        public synchronized void clear(){data=null;invalidate();}
        public synchronized OntologyInquiry.Dataset dataset(String schema,boolean refresh,Supplier<OntologyInquiry.Dataset> loader){
            if(refresh||data!=null&&!data.schema().equals(schema))clear();
            if(data==null){var loaded=Objects.requireNonNull(loader.get());if(!schema.equals(loaded.schema()))throw stale();data=loaded;}
            return data;
        }
        public synchronized OntologyInquiry.Search search(String schema,String question,String anchor){
            invalidate();SelectAiTest.question(question);
            if(data==null||!data.schema().equals(schema)||question.codePoints().anyMatch(c->c>=0xD800&&c<=0xDFFF))throw stale();
            anchor=Objects.toString(anchor,"");String table=anchor;
            if(!anchor.isEmpty()&&data.entries().stream().noneMatch(e->e.document().source().table().equals(table)))throw stale();
            // Same bounded path finder, with the test screen's 16,000-character input contract.
            return search=OntologyPaths.search(data,question,anchor);
        }
        public synchronized Snapshot choose(String id,String route,JsonMapper json){
            if(search==null||!search.id().equals(id))throw stale();
            var chosen=OntologyInquiry.chooseRoute(search,route);var entries=OntologyInquiry.selected(chosen,data);
            String source=OntologyInquiry.payload(chosen,entries,json);
            return selected=new Snapshot(chosen.schema(),chosen.question(),route,entries.stream().map(OntologyContext::reference).toList(),
                    OntologyQueryService.hash(source),source,entries);
        }
        public synchronized Snapshot resolve(boolean enabled,String hash,String question){
            if(!enabled){if(hash!=null&&!hash.isBlank())throw stale();return null;}
            if(selected==null||!selected.hash().equals(hash)||!selected.question().equals(question))throw stale();return selected;
        }
    }
}
