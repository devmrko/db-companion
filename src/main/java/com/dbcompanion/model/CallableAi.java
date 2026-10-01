package com.dbcompanion.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class CallableAi {
    private CallableAi() {}
    public record Status(String owner,String state,List<String> errors,List<String> profiles,List<String> tables) {
        public Status {errors=List.copyOf(errors);profiles=List.copyOf(profiles);tables=List.copyOf(tables);}
    }
    public record Request(String question,String profile,boolean glossary,boolean ontology,List<String> tables,String mode,int maxRows) {
        public Request {
            question=BusinessGlossary.text(question,8000,true);profile=BusinessGlossary.text(profile,128,true);
            tables=tables==null?List.of():List.copyOf(tables);
            if(!List.of("CONTEXT","SQL","QUERY").contains(Objects.toString(mode,""))||maxRows<1||maxRows>1000
                ||tables.size()>10||tables.stream().distinct().count()!=tables.size()||ontology&&tables.isEmpty())throw new IllegalArgumentException("Invalid callable options");
            for(String table:tables)BusinessGlossary.text(table,128,true);
            if(!ontology)tables=List.of();
        }
    }
    public record Install(String token,String owner,String fingerprint,String sql,Instant expires) {}
    public record Run(String token,String owner,Request request,String fingerprint,Instant expires) {}
    public record Access(String username,boolean granted) {}
    public record Grant(String token,String owner,String username,String sql,Instant expires) {}
}
