package com.dbcompanion.model;

import com.dbcompanion.service.OntologyDiscovery;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable plans and call receipts. Candidates themselves belong to ontology revisions/RDF. */
public final class OntologyDiscoveryArchive {
    private OntologyDiscoveryArchive(){}
    public static final String PLAN="ONTOLOGY_DISCOVERY_PLAN",CALL="ONTOLOGY_DISCOVERY_CALL";
    public record Run(int format,String id,String database,String schema,AiAssistant.Profile profile,
                      List<OntologyAnalysis.Reference> sources,Map<String,String> definitions,int tables,int calls,String createdAt){
        public Run{sources=List.copyOf(sources);definitions=Map.copyOf(definitions);}
    }
    public record Receipt(String runId,int index,String stage,String raw,List<OntologyRelations.Relation> relations,
                          List<OntologyDiscovery.Issue> issues,String diagnostic){
        public Receipt{relations=List.copyOf(relations);issues=List.copyOf(issues);}
        public Receipt(String runId,int index,String stage,String raw,List<OntologyRelations.Relation> relations,List<OntologyDiscovery.Issue> issues){this(runId,index,stage,raw,relations,issues,"");}
    }
    public record Call(String id,String state,Receipt receipt){}
    public record Summary(int index,String state,String stage,int saved,List<OntologyDiscovery.Issue> issues){}
    public record Saved(String id,String recordedAt,String profile,int tables,int calls){}
    public record Page(List<Saved> rows,String next){public Page{rows=List.copyOf(rows);}}
    public static String callId(String runId,int index){QueryArchive.id(runId);if(index<0)throw new Ontology.Failure(400,"invalid");return UUID.nameUUIDFromBytes((runId+":"+index).getBytes(StandardCharsets.UTF_8)).toString();}
}
