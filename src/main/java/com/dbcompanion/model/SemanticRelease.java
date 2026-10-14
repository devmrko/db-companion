package com.dbcompanion.model;

import java.util.*;
import com.dbcompanion.service.OntologyQueryService;
import tools.jackson.databind.json.JsonMapper;

/** Immutable semantic evidence; never a physical database restore. */
public final class SemanticRelease {
    private SemanticRelease(){}
    public static final String TYPE="SEMANTIC_RELEASE",DEPLOY="SEMANTIC_DEPLOY",POINTER="SEMANTIC_POINTER";
    public static final String POINTER_ID="1853af8e-a445-4d27-b590-03dffed30cb2";
    public static final String PREFERENCE="SEMANTIC_PREFERENCE",PREFERENCE_ID="aed9dc50-bedd-411e-973b-a5083bbbf3db";
    public record Preference(long revision,String selection){public Preference{if(revision<0)throw invalid();if(!Set.of("LIVE","DEFAULT").contains(selection))QueryArchive.id(selection);}}
    public record Content(int format,String database,String owner,String name,List<BusinessGlossary.Term> terms,List<Ontology.Entry> entries,String parentId){
        public Content(int format,String database,String owner,String name,List<BusinessGlossary.Term> terms,List<Ontology.Entry> entries){this(format,database,owner,name,terms,entries,"");}
        public Content {
            if(format!=1)throw invalid();Ontology.name(owner);name=BusinessGlossary.text(name,120,true);
            parentId=Objects.toString(parentId,"");if(!parentId.isEmpty())QueryArchive.id(parentId);
            terms=List.copyOf(terms);entries=List.copyOf(entries);
            if(terms.size()>BusinessGlossary.MAX_TERMS||entries.size()>100||entries.isEmpty())throw invalid();
            if(terms.stream().anyMatch(t->!t.enabled())||terms.stream().map(BusinessGlossary.Term::id).distinct().count()!=terms.size())throw invalid();
            if(entries.stream().map(e->e.document().source().table()).distinct().count()!=entries.size())throw invalid();
            for(var e:entries)if(!owner.equals(e.document().source().schema())||!database.equals(e.document().source().database()))throw invalid();
        }
    }
    public record Stored(String hash,String body) {
        public Content read(JsonMapper json){
            if(body==null||body.length()>QueryArchive.MAX_JSON-1000||!OntologyQueryService.hash(body).equals(hash))throw invalid();
            return json.readValue(body,Content.class);
        }
    }
    public record Version(String id,String actor,String recordedAt,String hash,Content content){}
    public record Head(long revision,String releaseId){public Head{if(revision<0)throw invalid();if(!releaseId.isEmpty())QueryArchive.id(releaseId);}}
    public record Deployment(String previous,String target,long revision,String reason){}
    public record TermRef(String id,long revision){public TermRef{BusinessGlossary.id(id);if(revision<1)throw invalid();}}
    public record SourceRef(String table,int revision){public SourceRef{Ontology.name(table);if(revision<1)throw invalid();}}
    public static AiAssistant.Failure invalid(){return BusinessGlossary.failure(409,"시멘틱 버전이 없거나 범위·무결성을 확인할 수 없습니다. 버전을 다시 선택해 주세요.");}
    public static final class State {
        private boolean resolved;
        private Version selected;
        public synchronized boolean resolved(){return resolved;}
        public synchronized Version selected(){return selected;}
        public synchronized void select(Version value){selected=value;resolved=true;}
        public synchronized void clear(){selected=null;resolved=false;}
    }
}
