package com.dbcompanion.service;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The model receives every chat message; only registered adapters can act on it. */
public final class AiConversationProtocol {
    private AiConversationProtocol() {}
    public static final Set<String> ACTIONS=Set.of("", "create", "clone", "draft", "suggest", "tables", "models", "review", "resume", "cancel", "history", "sources", "glossary", "glossary-list", "attribute", "query", "navigate", "permissions", "target", "input");
    private static final Set<String> FIELDS=Set.of("name","provider","model","credential_name","region","oci_compartment_id","comments","conversation","tables");
    public record Result(String answer,String action,JsonNode arguments,List<AiSetupToolLoop.Execution> executions,int modelCalls) {}
    /** Returned only to the requesting session; never log the raw provider response. */
    public static final class ResponseFailure extends IllegalArgumentException {
        private final int modelCalls;private final List<AiSetupToolLoop.Execution> executions;private final String response;
        ResponseFailure(String code,int modelCalls,List<AiSetupToolLoop.Execution> executions,String response){super(code);this.modelCalls=modelCalls;this.executions=List.copyOf(executions);this.response=response!=null&&response.length()<=16000?response:"";}
        public int modelCalls(){return modelCalls;}public List<AiSetupToolLoop.Execution> executions(){return executions;}public String response(){return response;}
    }
    public static JsonNode object(String raw,JsonMapper json){
        if(raw==null||raw.length()>16000)throw new IllegalArgumentException("CONVERSATION_RESPONSE_SIZE");
        // A single JSON code block is formatting, not another command. Still parse exactly one object.
        String text=raw.strip();
        if(text.startsWith("```json\n")&&text.endsWith("```"))text=text.substring(8,text.length()-3).strip();
        else if(text.startsWith("```\n")&&text.endsWith("```"))text=text.substring(4,text.length()-3).strip();
        text=escapedLines(text);
        final JsonNode node;
        try{node=json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);}
        catch(RuntimeException ex){
            // Some text models append a simulated observation after a read request. Discard it:
            // only execute the first registered READ, then ask the model with the real service result.
            // Final answers never get this accommodation; no fabricated result/action is consumed.
            final JsonNode first;
            try{first=json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);}
            catch(RuntimeException malformed){throw new IllegalArgumentException("CONVERSATION_INVALID_JSON");}
            if(first!=null&&first.isObject()&&first.path("type").asString("").equals("tool_call"))return first;
            throw new IllegalArgumentException("CONVERSATION_INVALID_JSON");
        }
        if(node==null||!node.isObject())throw new IllegalArgumentException("CONVERSATION_NON_OBJECT");
        if(!node.path("type").isString())throw new IllegalArgumentException("CONVERSATION_MISSING_TYPE");return node;
    }
    /** Text providers can return literal line breaks inside JSON strings. Preserve their text value. */
    private static String escapedLines(String raw){
        var out=new StringBuilder();boolean quoted=false,escape=false;
        for(char c:raw.toCharArray()){
            if(quoted&&!escape&&(c=='\n'||c=='\r'||c=='\t')){out.append(c=='\n'?"\\n":c=='\r'?"\\r":"\\t");continue;}
            out.append(c);if(escape){escape=false;continue;}if(quoted&&c=='\\'){escape=true;continue;}if(c=='\"')quoted=!quoted;
        }
        return out.toString();
    }
    public static void validateAction(String action,JsonNode args){
        if(!ACTIONS.contains(action)||!args.isObject())throw new IllegalArgumentException("CONVERSATION_ACTION");
        Set<String> allowed=switch(action){case "draft","suggest"->FIELDS;case "clone","target"->Set.of("profile");case "tables"->Set.of("filter");
            case "attribute"->Set.of("attribute","value");case "input"->Set.of("value");case "navigate"->Set.of("path");case "permissions"->Set.of("topic");default->Set.of();};
        for(String key:args.propertyNames()){
            if(!allowed.contains(key))throw new IllegalArgumentException("CONVERSATION_ARGUMENT");var value=args.path(key);
            if(key.equals("tables")){
                if(!value.isArray()||value.size()>100)throw new IllegalArgumentException("CONVERSATION_TABLES");
                for(var table:value){
                    if(table.isString()&&table.asString().matches("[A-Z][A-Z0-9_$#]{0,124}(?:\\.[A-Z][A-Z0-9_$#]{0,124})?"))continue;
                    if(!table.isObject())throw new IllegalArgumentException("CONVERSATION_TABLE_OBJECT_REQUIRED");
                    if(table.size()!=2)throw new IllegalArgumentException("CONVERSATION_TABLE_KEYS");
                    if(!identifier(table.path("owner")))throw new IllegalArgumentException("CONVERSATION_TABLE_OWNER");
                    if(!identifier(table.path("name")))throw new IllegalArgumentException("CONVERSATION_TABLE_NAME");
                }
            }else if(Set.of("comments","conversation").contains(key)){if(!value.isBoolean())throw new IllegalArgumentException("CONVERSATION_BOOLEAN");}
            else if(!value.isString()||value.asString().length()>1000||value.asString().indexOf('\0')>=0)throw new IllegalArgumentException("CONVERSATION_VALUE");
        }
        if(Set.of("draft","suggest").contains(action)&&args.has("name")&&!identifier(args.path("name")))throw new IllegalArgumentException("CONVERSATION_NAME");
        Set<String> required=switch(action){case "target"->Set.of("profile");case "attribute"->Set.of("attribute");case "navigate"->Set.of("path");case "permissions"->Set.of("topic");case "input"->Set.of("value");default->Set.of();};
        if(!args.propertyNames().containsAll(required))throw new IllegalArgumentException("CONVERSATION_ARGUMENT_REQUIRED");
        if(Set.of("draft","suggest").contains(action)&&args.isEmpty())throw new IllegalArgumentException("CONVERSATION_ARGUMENT_REQUIRED");
    }
    private static boolean identifier(JsonNode value){return value.isString()&&value.asString().matches("[A-Z][A-Z0-9_$#]{0,124}");}
    public static Result run(String prompt,JsonMapper json,Set<String> tools,Function<String,String> model,BiFunction<String,JsonNode,Object> service){
        return run(prompt,json,tools,model,service,false);
    }
    public static Result run(String prompt,JsonMapper json,Set<String> tools,Function<String,String> model,BiFunction<String,JsonNode,Object> service,boolean reviewUnacted){
        var executions=new ArrayList<AiSetupToolLoop.Execution>();var called=new HashSet<String>();var transcript=new StringBuilder(prompt);
        for(int call=1;call<=7;call++){
            if(transcript.length()>64000)throw new IllegalArgumentException("CONVERSATION_CONTEXT_LIMIT");
            String reply=model.apply(transcript.toString());final JsonNode node;
            // Normal conversation needs no action envelope. Plain text can only be displayed;
            // it can never dispatch a Tool, edit a draft, or authorize a database operation.
            if(reply!=null&&!reply.isBlank()&&reply.length()<=16000&&!reply.stripLeading().startsWith("{")&&!reply.stripLeading().startsWith("[")&&!reply.stripLeading().startsWith("```")){
                if(reviewUnacted&&call==1){review(transcript,reply,json);continue;}
                return new Result(reply,"",json.createObjectNode(),List.copyOf(executions),call);
            }
            try{node=object(reply,json);}catch(IllegalArgumentException ex){throw new ResponseFailure(ex.getMessage(),call,executions,reply);}
            if(node.path("type").asString().equals("final")){
                if(reviewUnacted&&call==1){review(transcript,reply,json);continue;}
                if(node.size()==2&&node.path("answer").isString()&&!node.path("answer").asString().isBlank())return new Result(node.path("answer").asString(),"",json.createObjectNode(),List.copyOf(executions),call);
                if(node.size()!=4||!node.path("answer").isString()||node.path("answer").asString().isBlank()||!node.path("action").isString())throw new IllegalArgumentException("CONVERSATION_FINAL");
                String action=node.path("action").asString();validateAction(action,node.path("arguments"));
                return new Result(node.path("answer").asString(),action,node.path("arguments"),List.copyOf(executions),call);
            }
            if(!node.path("type").asString().equals("tool_call")||node.size()!=3||!node.path("name").isString()||!node.path("arguments").isObject())throw new IllegalArgumentException("CONVERSATION_TOOL_FORMAT");
            String name=node.path("name").asString(),key=name+json.writeValueAsString(node.path("arguments"));
            if(call==7||!tools.contains(name)||!called.add(key))throw new IllegalArgumentException("CONVERSATION_TOOL_LIMIT");
            String result=json.writeValueAsString(service.apply(name,node.path("arguments")));
            if(result.length()>20000)throw new IllegalArgumentException("CONVERSATION_TOOL_RESULT_LIMIT");
            executions.add(new AiSetupToolLoop.Execution(executions.size()+1,name,result));
            // Workspace Tools return directly: their actual rendered UI is the result. This avoids
            // another generation attempting a second operation instead of ending the current turn.
            if(AiConversationWorkflows.ACTIONS.containsKey(name))return new Result(json.readTree(result).path("answer").asString("요청한 작업을 작업 영역에 표시합니다."),"",json.createObjectNode(),List.copyOf(executions),call);
            transcript.append("\nSERVER_TOOL_EXCHANGE ").append(json.writeValueAsString(Map.of("request",node,"result",json.readTree(result))));
            transcript.append("\nUse this actual service result to fulfill USER_MESSAGE. Return exactly one JSON tool_call, or {\"type\":\"final\",\"answer\":\"answer using actual evidence\"}. Do not put actions in a final answer; workspace changes require the registered workflow Tool. No simulated observations or repeated confirmation for an already requested draft edit.");
        }
        throw new IllegalArgumentException("CONVERSATION_CALL_LIMIT");
    }
    private static void review(StringBuilder transcript,String answer,JsonMapper json){
        transcript.append("\nUNEXECUTED_ASSISTANT_DRAFT ").append(json.writeValueAsString(answer))
            .append("\nONE_COMPLETION_CHECK: No service has run. Compare USER_MESSAGE with the registered Tools. If the user requested an available workspace operation or actual metadata, invoke its Tool NOW instead of saying you can do it or need to open the workspace. The workspace is available. Preview/display requests still require the preview/display Tool even when the user says do not apply; these Tools do not write to DB. If only an explanation or clarification is needed, answer normally; do not invent an action or resolve an ambiguous reference by guessing. This is the last completion check, within the original call budget.");
    }
}
