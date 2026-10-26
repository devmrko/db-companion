package com.dbcompanion.service;

import com.dbcompanion.model.BusinessGlossary;
import java.util.*;

/** Deterministic enrichment: dictionary text is evidence, never executable instructions. */
public final class OntologyQuestionGrounding {
    private OntologyQuestionGrounding(){}
    public record Result(String original,String interpreted,List<BusinessGlossary.Term> terms){public Result{terms=List.copyOf(terms);}}
    /** Retrieval terms are not the evidence payload. Keep definitions and SQL out of token expansion. */
    public static BusinessGlossary.Analysis searchTerms(BusinessGlossary.Analysis original,Result grounding,List<String> concepts){
        var words=new LinkedHashMap<String,BusinessGlossary.Token>();
        original.tokens().forEach(t->words.putIfAbsent(t.token(),t));
        grounding.terms().forEach(t->words.putIfAbsent(t.term(),new BusinessGlossary.Token(t.term(),0,t.term().length())));
        concepts.forEach(c->words.putIfAbsent(c,new BusinessGlossary.Token(c,0,c.length())));
        return new BusinessGlossary.Analysis(original.mode(),List.copyOf(words.values()),original.language(),original.policy(),original.lexer());
    }
    public static Result resolve(BusinessGlossary.Search search,List<String> ids){
        if(search.more()||ids==null||ids.size()>BusinessGlossary.MAX_SELECTED||new HashSet<>(ids).size()!=ids.size())throw BusinessGlossary.invalid();
        var known=new HashMap<String,BusinessGlossary.Term>();search.hits().forEach(h->known.put(h.term().id(),h.term()));
        if(!known.keySet().containsAll(ids))throw BusinessGlossary.stale();
        for(var target:search.targets())if(target.termIds().stream().filter(ids::contains).count()!=1)
            throw BusinessGlossary.failure(400,"각 용어 표현에 사용할 정의를 하나씩 선택해 주세요.");
        var terms=ids.stream().map(known::get).toList();
        String interpreted=search.question()+terms.stream().map(t->"\n["+t.term()+"] "+t.definition()+(t.criteria().isBlank()?"":"\n"+t.criteria())).reduce("",String::concat);
        if(interpreted.length()>16000)throw BusinessGlossary.failure(413,"용어 정의가 너무 많습니다. 질문 범위를 좁혀 주세요.");
        return new Result(search.question(),interpreted,terms);
    }
}
