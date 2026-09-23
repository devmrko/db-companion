package com.dbcompanion.model;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.common.i18n.UiMessages;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Creation plans are session-local, bounded and never contain credential secrets. */
public final class AiCreation {
    private AiCreation() {}
    private static final JsonMapper EXACT_JSON=JsonMapper.builder().enable(
            tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
            tools.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static JsonNode parse(String value){return EXACT_JSON.readTree(value);}
    public enum Kind { PROFILE, TEAM, AGENT, TASK, TOOL }
    public static final int MAX_BYTES=262144, MAX_COPY_BYTES=2_000_000;
    private static final Set<String> NUMBERS=Set.of("temperature","max_tokens","seed","top_p","frequency_penalty","presence_penalty","short_term_memory_length","long_term_memory_length");
    private static final Set<String> STRUCTURED=Set.of("object_list","tools","agents","tool_params","tool_inputs","stop_tokens");
    public record Input(String schema,Kind kind,String source,String sourceVersion,String name,String description,String status,String attributes,@com.fasterxml.jackson.annotation.JsonProperty(required=true) boolean copyFeedback) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unknown creation field: "+key);}
    }
    public record Form(String sourceVersion,String description,String attributes,boolean historyReady,List<String> credentials,Map<String,List<String>> references) {}
    public record Feedback(String question,String attributes) {}
    public record Preview(String token,Input input,List<Feedback> feedback) {}
    public record Result(boolean verified,String stage,String detail,String name,int copied,int total,boolean done) {}
    public static MetadataEditException error(int status,String key) {return new MetadataEditException(status,key,UiMessages.text("creation."+key,key));}
    public static void scope(String login,String selected,String schema){if(!Objects.equals(selected,schema))throw error(409,"schemaChanged");if(!Objects.equals(login,schema))throw error(403,"ownerOnly");}
    public static String name(String value) {
        if(value==null||!value.matches("[A-Z][A-Z0-9_$#]{0,124}"))throw error(400,"nameInvalid");return value;
    }
    public static void text(String value,int limit){
        if(value==null||value.indexOf('\0')>=0||value.getBytes(StandardCharsets.UTF_8).length>limit)throw error(400,"size");
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw error(400,"size");}else if(Character.isLowSurrogate(c))throw error(400,"size");}
    }
    public static Map<String,String> attributes(Map<String,Object> snapshot){
        var out=new TreeMap<String,String>();if(snapshot.get("attributes") instanceof Map<?,?> m)m.forEach((k,v)->{if(v==null)throw error(400,"attributesInvalid");out.put(k.toString(),v.toString());});return out;
    }
    public static String attributesJson(Map<String,Object> snapshot,JsonMapper json){
        var result=new TreeMap<String,Object>();attributes(snapshot).forEach((k,v)->{
            if(!ProfileEditPolicy.editableAttribute(k))throw error(400,"secretAttribute");
            Object value=v;
            if(NUMBERS.contains(k)){
                try{value=new java.math.BigDecimal(v);}catch(NumberFormatException ex){throw error(400,"attributesInvalid");}
            }else if(STRUCTURED.contains(k)||ProfileEditPolicy.BOOLEANS.contains(k)||Set.of("supervisor","enable_human_tool").contains(k)){
                try{value=parse(v);}catch(RuntimeException ex){throw error(400,"attributesInvalid");}
            }
            result.put(k,value);
        });return json.writeValueAsString(result);
    }
    public static String value(JsonNode node){return node.isString()?node.stringValue():node.toString();}
    public static JsonNode validate(Input in,JsonMapper json){
        if(in==null||in.kind()==null||in.source()==null||in.description()==null)throw error(400,"attributesInvalid");
        name(in.name());text(in.description(),MAX_BYTES);text(in.attributes(),MAX_BYTES);
        if(!in.source().isEmpty())TeamEditPolicy.identifier(in.source());if(in.sourceVersion()==null||!in.sourceVersion().matches("[a-f0-9]{64}"))throw error(400,"sourceChanged");
        if(!Set.of("ENABLED","DISABLED").contains(Objects.toString(in.status(),"")))throw error(400,"attributesInvalid");
        if(in.copyFeedback()&&(in.kind()!=Kind.PROFILE||in.source().isEmpty()))throw error(400,"copyUnavailable");
        if(in.copyFeedback())AiFeedback.tableName(in.name());
        JsonNode root;try{root=parse(in.attributes());}catch(RuntimeException ex){throw error(400,"attributesInvalid");}
        if(root==null||!root.isObject()||root.isEmpty()||root.size()>100)throw error(400,"attributesInvalid");
        noSecrets(root);
        for(var entry:root.properties()){
            String key=entry.getKey();var v=entry.getValue();
            if(!ProfileEditPolicy.editableAttribute(key))throw error(400,"secretAttribute");
            if(v.isNull())throw error(400,"attributesInvalid");
            String raw=value(v);
            if(in.kind()==Kind.PROFILE){
                if(!Set.of("additional_instructions","role").contains(key))ProfileEditPolicy.input(new ProfileEdit.Target(in.schema(),in.name(),key),raw,json);
                else {text(raw,MAX_BYTES);if(!v.isString()||raw.isBlank())throw error(400,"attributesInvalid");}
            }
            else if(in.kind()==Kind.TEAM)TeamEditPolicy.input(new TeamEdit.Target(in.schema(),in.name(),key),raw,json);
            else if(key.equals("supervisor")&&in.kind()==Kind.AGENT){if(!v.isBoolean())throw error(400,"attributesInvalid");}
            else AgentObjectEditPolicy.input(new AgentObjectEdit.Target(in.schema(),AgentCatalog.Kind.valueOf(in.kind().name()),in.name(),key),raw,json);
        }
        switch(in.kind()){
            case PROFILE -> {required(root,"provider");if(!root.get("provider").isString())throw error(400,"attributesInvalid");}
            case TEAM -> {required(root,"agents");required(root,"process");}
            case AGENT -> {required(root,"role");required(root,"profile_name");}
            case TASK -> required(root,"instruction");
            case TOOL -> {if(root.has("function")==root.has("tool_type"))throw error(400,"toolChoice");}
        }
        return root;
    }
    private static void required(JsonNode n,String key){if(!n.has(key)||value(n.get(key)).isBlank())throw error(400,"required");}
    private static void noSecrets(JsonNode n){if(n.isObject())for(var e:n.properties()){if(!ProfileEditPolicy.editableAttribute(e.getKey().toLowerCase(Locale.ROOT)))throw error(400,"secretAttribute");noSecrets(e.getValue());}else if(n.isArray())for(var v:n)noSecrets(v);}
    public static String fingerprint(Object value,JsonMapper json){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical(json.valueToTree(value)))));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private static Object canonical(JsonNode value){if(value.isObject()){var out=new TreeMap<String,Object>();value.properties().forEach(e->out.put(e.getKey(),canonical(e.getValue())));return out;}if(value.isArray()){var out=new ArrayList<Object>();value.forEach(v->out.add(canonical(v)));return out;}return value;}
    public static JsonNode feedback(Feedback row,JsonMapper json){
        text(row.question(),MAX_BYTES);text(row.attributes(),MAX_BYTES);JsonNode n;try{n=parse(row.attributes());}catch(RuntimeException ex){throw error(409,"copyUnsupported");}
        if(!n.isObject()||!"negative".equals(n.path("feedback_type").asString())||!n.path("sql_text").isString()||n.path("sql_text").asString().isBlank()
                ||!n.path("response").isString()||n.path("response").asString().isBlank()||n.hasNonNull("sql_id"))throw error(409,"copyUnsupported");
        if(n.hasNonNull("feedback_content")&&!n.get("feedback_content").isString())throw error(409,"copyUnsupported");
        var allowed=Set.of("feedback_type","sql_id","sql_text","response","feedback_content");
        if(n.properties().stream().anyMatch(e->!allowed.contains(e.getKey())))throw error(409,"copyUnsupported");return n;
    }
    /** Validate while fetching, before accumulating a complete copy plan in memory. */
    public static final class FeedbackBatch {
        private int bytes;
        private final Set<String> keys=new HashSet<>(),questions=new HashSet<>();
        public void add(Feedback row){
            var n=feedback(row,EXACT_JSON);
            int size=row.question().getBytes(StandardCharsets.UTF_8).length+row.attributes().getBytes(StandardCharsets.UTF_8).length;
            if(size>MAX_COPY_BYTES-bytes)throw error(413,"copyLimit");
            if(!keys.add(n.get("sql_text").stringValue())||!questions.add(row.question()))throw error(409,"copyDuplicate");
            bytes+=size;
        }
    }
    public static void validateFeedback(List<Feedback> rows,JsonMapper json){
        var batch=new FeedbackBatch();rows.forEach(batch::add);
    }
    public static boolean feedbackMatches(List<Feedback> expected,List<Feedback> actual,JsonMapper json){
        if(expected.size()!=actual.size())return false;var map=new HashMap<String,JsonNode>();
        for(var r:expected)map.put(r.question(),parse(r.attributes()));
        if(map.size()!=expected.size())return false;
        for(var r:actual)if(!Objects.equals(map.remove(r.question()),parse(r.attributes())))return false;return map.isEmpty();
    }
    public static boolean matches(Input in,Map<String,Object> actual,JsonMapper json){
        if(!Boolean.TRUE.equals(actual.get("exists")))return false;
        var fields=(Map<?,?>)actual.get(in.kind()==Kind.PROFILE?"profile":"object");
        if(!Objects.equals(fields.get("NAME"),in.name())&&!Objects.equals(fields.get("PROFILE_NAME"),in.name()))return false;
        if(!in.status().equalsIgnoreCase(Objects.toString(fields.get("STATUS"),""))||!Objects.equals(Objects.toString(fields.get("DESCRIPTION"),""),in.description()))return false;
        var wanted=parse(in.attributes());var observed=attributes(actual);
        for(var e:wanted.properties()){
            String v=observed.get(e.getKey()),desired=value(e.getValue());if(Objects.equals(v,desired))continue;
            if(v==null)return false;
            if(e.getValue().isArray()||e.getValue().isObject()||e.getValue().isNumber()||e.getValue().isBoolean()){
                try{if(!parse(v).equals(e.getValue()))return false;}catch(RuntimeException ex){return false;}
            }else if(!ProfileEditPolicy.equivalent(e.getKey(),desired,v,json))return false;
        }
        return true; // Oracle may add documented default attributes; the full readback is archived.
    }
    public static final class Plan {
        public final String token=UUID.randomUUID().toString();public final Instant expires=Instant.now().plusSeconds(1800);
        public final Input input;public final Map<String,Object> original;public final List<Feedback> feedback;
        public boolean attempted,blocked;public int copied;public Map<String,Object> created;
        public Plan(Input input,Map<String,Object> original,List<Feedback> feedback){this.input=input;this.original=Map.copyOf(original);this.feedback=List.copyOf(feedback);}
        public void require(String token){if(!this.token.equals(token)||!Instant.now().isBefore(expires)||blocked)throw error(409,"expired");}
        public Preview preview(){return new Preview(token,input,feedback);}
    }
    public static final class State {
        private Plan plan;
        public void clear(){plan=null;}
        public void put(Plan value){plan=value;}
        public Plan get(String token){if(plan==null)throw error(409,"expired");plan.require(token);return plan;}
    }
}
