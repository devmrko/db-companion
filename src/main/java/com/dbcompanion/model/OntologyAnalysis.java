package com.dbcompanion.model;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Version references and selected semantic suggestions, never source data samples. */
public final class OntologyAnalysis {
    private OntologyAnalysis(){}
    public record Reference(String database,String schema,String table,String documentId,int revision,String state){}
    public record Context(List<Reference> sources,List<String> relations,List<String> omitted){
        public Context {sources=List.copyOf(sources);relations=List.copyOf(relations);omitted=List.copyOf(omitted);}
    }
    public record Bundle(Context context,String payload){}
    public record Recommendation(String field,String name,String value,String reason,String uncertainty){}
    public record Edit(String field,String name,String value){}
    public record Evidence(List<Reference> sources,List<Recommendation> accepted,String generatedAt){
        public Evidence {sources=List.copyOf(sources);accepted=List.copyOf(accepted);}
    }
    public static void validate(Evidence evidence){
        if(evidence==null)return;
        try{
            if(evidence.sources().isEmpty()||evidence.sources().size()>10||evidence.accepted().isEmpty()||evidence.accepted().size()>1002)throw new IllegalArgumentException();
            java.time.Instant.parse(evidence.generatedAt());
            for(var r:evidence.sources()){
                Ontology.name(r.schema());Ontology.name(r.table());
                if(r.database()==null||r.revision()<1||!Set.of("DRAFT","APPROVED").contains(r.state())||!UUID.fromString(r.documentId()).toString().equals(r.documentId()))throw new IllegalArgumentException();
            }
            for(var r:evidence.accepted()){
                if(!Set.of("concept","description","relation").contains(r.field())||r.name()==null||r.value().isBlank())throw new IllegalArgumentException();
                OntologyWizard.concise(r.value(),r.reason(),r.uncertainty());
            }
        }catch(RuntimeException ex){throw new Ontology.Failure(409,"mismatch");}
    }
}
