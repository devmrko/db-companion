package com.dbcompanion.service;
import com.dbcompanion.model.Ontology;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** LLM output remains advisory; it cannot supply SQL, grants or new graph edges. */
public final class OntologyAssistantReply {
    private OntologyAssistantReply(){}
    public static final class InvalidReply extends Ontology.Failure {
        private final String field;
        InvalidReply(String field){super(422,"query.aiReplyInvalid");this.field=field;}
        @Override public String getMessage(){return super.getMessage()+" ["+field+"]";}
    }
    private static InvalidReply invalid(String field){return new InvalidReply(field);}
    public record Interpretation(String summary,List<String> concepts,List<String> questions,List<String> termIds){}
    public record Recommendation(String routeId,String reason,List<String> evidence,List<String> questions){}
    public record Candidate(String id,String use,String reason,boolean selected){}
    public record Plan(String mode,String routeId,List<String> tables,List<Candidate> candidates,String reason,List<String> questions){}
    public static Interpretation ontologyQuestion(String output,JsonMapper json){
        var n=object(output,json,Set.of("summary","concepts","questions"));
        var summary=string(n,"summary",2000);var concepts=list(n,"concepts",12,80);var questions=list(n,"questions",5,500);
        if(summary.isBlank()||questions.isEmpty()&&concepts.isEmpty())throw invalid("summary/concepts: ontology question and search terms required");
        return new Interpretation(summary,concepts,questions,List.of());
    }
    public static Plan plan(String output,OntologyInquiry.Search search,Set<String> tables,Set<String> candidates,JsonMapper json){
        var n=object(output,json,Set.of("mode","routeId","tables","candidates","reason","questions"));
        String mode=string(n,"mode",32),route=n.path("routeId").isNull()&&!"PATH".equals(mode)?"":string(n,"routeId",128);var sources=list(n,"tables",10,128);
        if(!Set.of("PATH","INDEPENDENT","REVIEW").contains(mode))throw invalid("mode");
        if(!tables.containsAll(sources))throw invalid("tables: unknown source");
        if(mode.equals("PATH")){
            var found=search.routes().stream().filter(r->r.id().equals(route)).findFirst().orElseThrow(()->invalid("routeId: unknown route"));
            if(!new HashSet<>(sources).equals(new HashSet<>(found.tables())))throw invalid("tables: route mismatch");
        }else if(!route.isEmpty())throw invalid("routeId: must be empty");
        if(mode.equals("INDEPENDENT")&&sources.isEmpty())throw invalid("tables: empty");
        var items=n.path("candidates");if(!items.isArray()||items.size()>100)throw invalid("candidates: array required, max 100");
        var result=new ArrayList<Candidate>();var seen=new HashSet<String>();
        for(var item:items){
            if(!item.isObject()||item.properties().stream().anyMatch(p->!Set.of("id","use","reason","selected").contains(p.getKey())))throw invalid("candidates: unexpected fields");
            String id=string(item,"id",256),use=string(item,"use",32),reason=string(item,"reason",1000);
            if(!candidates.contains(id)||!seen.add(id))throw invalid("candidates.id: unknown or duplicate");
            if(!Set.of("REFERENCE","EXCLUDE","UNRESOLVED").contains(use))throw invalid("candidates.use");
            if(!item.path("selected").isBoolean())throw invalid("candidates.selected: boolean required");
            boolean selected=item.path("selected").asBoolean();if(selected&&!use.equals("REFERENCE"))throw invalid("candidates.selected: only REFERENCE allowed");
            result.add(new Candidate(id,use,reason,selected));
        }
        return new Plan(mode,route,sources,List.copyOf(result),string(n,"reason",3000),list(n,"questions",5,500));
    }
    private static JsonNode object(String text,JsonMapper json,Set<String> keys){
        if(text==null||text.length()>16000)throw invalid("response: empty or exceeds 16000 characters");
        text=text.strip();
        if(text.startsWith("```json\n")&&text.endsWith("```"))text=text.substring(8,text.length()-3).strip();
        else if(text.startsWith("```\n")&&text.endsWith("```"))text=text.substring(4,text.length()-3).strip();
        final JsonNode n;try{n=json.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);}catch(RuntimeException ex){throw invalid("response: malformed JSON");}
        if(n==null||!n.isObject())throw invalid("response: object required");
        if(n.properties().stream().anyMatch(p->!keys.contains(p.getKey())))throw invalid("response: unexpected fields");return n;
    }
    private static String string(JsonNode n,String key,int max){var v=n.path(key);if(!v.isString()||v.asString().length()>max)throw invalid(key+": string required, max "+max);return v.asString();}
    private static List<String> list(JsonNode n,String key,int max,int length){var v=n.path(key);if(!v.isArray()||v.size()>max)throw invalid(key+": array required, max "+max);var out=new ArrayList<String>();for(var e:v){if(!e.isString()||e.asString().isBlank()||e.asString().length()>length)throw invalid(key+": invalid item");out.add(e.asString());}return List.copyOf(new LinkedHashSet<>(out));}
    public static Interpretation interpretation(String output,Set<String> known,JsonMapper json){
        var n=object(output,json,Set.of("summary","concepts","questions","termIds"));var ids=list(n,"termIds",30,128);if(!known.containsAll(ids))throw new Ontology.Failure(422,"query.invalid");
        return new Interpretation(string(n,"summary",2000),list(n,"concepts",12,128),list(n,"questions",5,500),ids);
    }
    public static Recommendation recommendation(String output,OntologyInquiry.Search search,JsonMapper json){
        var n=object(output,json,Set.of("routeId","reason","evidence","questions"));String id=string(n,"routeId",128);var evidence=list(n,"evidence",30,128);
        var route=search.routes().stream().filter(r->r.id().equals(id)).findFirst();
        if(!id.isEmpty()&&(route.isEmpty()||evidence.isEmpty()||!route.get().evidence().containsAll(evidence))||id.isEmpty()&&!evidence.isEmpty())throw new Ontology.Failure(422,"query.invalid");
        return new Recommendation(id,string(n,"reason",3000),evidence,list(n,"questions",5,500));
    }
}
