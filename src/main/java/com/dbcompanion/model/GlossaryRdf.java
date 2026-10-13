package com.dbcompanion.model;

import java.util.List;
import java.util.Set;

/** User-declared meaning and fully qualified source mappings; never executable rules. */
public final class GlossaryRdf {
    private GlossaryRdf() {}
    public record Mapping(String schema,String table,String column,String role,String aggregation) {
        public Mapping {
            schema=identifier(schema);table=identifier(table);column=identifier(column);
            if(!Set.of("VALUE","DATE","IDENTIFIER","FILTER","JOIN").contains(role))throw BusinessGlossary.invalid();
            aggregation=BusinessGlossary.text(aggregation,32,false);
            if(!Set.of("","SUM","COUNT","COUNT_DISTINCT","MAX","MIN").contains(aggregation))throw BusinessGlossary.invalid();
        }
    }
    public record Definition(String domain,String unit,String scope,String grain,List<String> rules,List<String> citations,List<Mapping> mappings) {
        public Definition {
            domain=BusinessGlossary.text(domain,256,false);unit=BusinessGlossary.text(unit,128,false);
            scope=BusinessGlossary.text(scope,1000,false);grain=BusinessGlossary.text(grain,1000,false);
            rules=bounded(rules,20,1000);citations=bounded(citations,20,1000);
            mappings=mappings==null?List.of():List.copyOf(mappings);
            if(mappings.size()>50||mappings.stream().distinct().count()!=mappings.size())throw BusinessGlossary.invalid();
        }
    }
    private static List<String> bounded(List<String> values,int count,int length){
        if(values==null)return List.of();if(values.size()>count)throw BusinessGlossary.invalid();
        return values.stream().map(v->BusinessGlossary.text(v,length,true)).distinct().toList();
    }
    private static String identifier(String value){
        String result=BusinessGlossary.text(value,128,true);
        if(!result.matches("[A-Z][A-Z0-9_$#]*"))throw BusinessGlossary.invalid();return result;
    }
}
