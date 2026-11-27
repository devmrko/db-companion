package com.dbcompanion.service;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Local workspace operations are separate from metadata reads and database writes. */
public final class AiConversationWorkflows {
    private AiConversationWorkflows() {}
    public static final Map<String,String> ACTIONS=Map.ofEntries(
        Map.entry("creation.start","create"),Map.entry("creation.clone","clone"),Map.entry("creation.updateDraft","draft"),Map.entry("creation.suggest","suggest"),
        Map.entry("creation.showTables","tables"),Map.entry("creation.showModels","models"),Map.entry("creation.preview","review"),Map.entry("workspace.resume","resume"),
        Map.entry("workspace.cancel","cancel"),Map.entry("workspace.selectTarget","target"),Map.entry("workspace.history","history"),Map.entry("workspace.sources","sources"),
        Map.entry("workspace.glossary","glossary"),Map.entry("workspace.glossaryList","glossary-list"),Map.entry("workspace.editAttribute","attribute"),Map.entry("workspace.query","query"),
        Map.entry("workspace.respond","input"),Map.entry("workspace.navigate","navigate"),Map.entry("workspace.adminGuidance","permissions"));
    private static Map<String,Object> string(String description){return Map.of("type","string","description",description);}
    private static Map<String,Object> schema(Map<String,Object> properties,List<String> required){return Map.of("type","object","properties",properties,"required",required,"additionalProperties",false);}
    public static List<AiConversationService.Tool> tools(){
        var fields=new LinkedHashMap<String,Object>();
        for(String key:List.of("name","provider","model","credential_name","region","oci_compartment_id"))fields.put(key,string(key.equals("name")?"New uppercase profile identifier":"Only this field; credential_name is a reference, never a password/key"));
        fields.put("comments",Map.of("type","boolean"));fields.put("conversation",Map.of("type","boolean"));
        fields.put("tables",Map.of("type","array","items",Map.of("type","string","description","Exact table name from creation.options, e.g. ACTIVITY. Do NOT include schema, owner, type or comments."),"description","Replaces selected tables. Preserve other selections unless user requests removal or only one table."));
        var out=new ArrayList<AiConversationService.Tool>();
        ACTIONS.forEach((name,action)->{
            Map<String,Object> props=switch(action){case "draft","suggest"->fields;case "clone","target"->Map.of("profile",string("Exact existing profile name"));case "tables"->Map.of("filter",string("Optional object name filter"));
                case "attribute"->Map.of("attribute",string("Registered profile attribute name"),"value",string("Optional requested value; boolean as true or false"));
                case "input"->Map.of("value",string("Explicit answer to the pending NON-creation workspace prompt; never put a question about the field here"));
                case "navigate"->Map.of("path",string("Exact path from menu catalogue"));case "permissions"->Map.of("topic",string("profile, credential, text, semantic, metadata-history, profile-history, network, dds, agent or table"));default->Map.of();};
            List<String> required=switch(action){case "target"->List.of("profile");case "attribute"->List.of("attribute");case "input"->List.of("value");case "navigate"->List.of("path");case "permissions"->List.of("topic");default->List.of();};
            String description=switch(action){
                case "create"->"Start a NEW profile draft. Does not create in DB. Use only when user asks to start.";
                case "clone"->"Load an existing source profile into a new draft, preserving the draft's new name. Original and Feedback are untouched.";
                case "draft"->"Update ONLY specified local draft fields, ONLY when user asks or accepts a recommendation. NEVER call on a question asking what exists, explanation, or advice. DB unchanged.";
                case "suggest"->"Record ONE concrete recommendation for the current draft. Does NOT change the draft. For 'another name' recommend a different name. User can accept in the next message.";
                case "tables"->"Display actual table choices without selecting or changing any table. Use for list/what exists questions.";
                case "models"->"Display current model/authentication candidates without changing them.";
                case "review"->"Display the existing creation preview. Does NOT apply or create. Requires name, model and tables.";
                case "resume"->"Resume the existing profile draft.";case "cancel"->"Discard local workspace draft; no database changes.";
                case "target"->"Select an existing configuration target. This is NOT the assistant model selection.";
                case "history"->"Read selected profile configuration history.";case "sources"->"Open existing enabled semantic-source attachment review, preserving previous connections. No apply.";
                case "glossary"->"Open a business-term draft editor. No save.";case "glossary-list"->"Read existing business terms and choose one to edit.";
                case "attribute"->"Open existing profile attribute comparison/confirmation. No save. This edits an existing profile, not a creation draft.";
                case "query"->"Open SELECT question workflow. Model generation and SQL execution each require separate confirmation.";
                case "input"->"Pass an explicitly supplied/accepted value to the current NON-creation workspace input. No auto-apply or SQL execution.";
                case "navigate"->"Show an actual registered menu link for a location question or an operation without a chat executor.";
                default->"Display existing role-aware ADMIN instruction SQL and verification SQL for the topic. No grants; current privilege status NOT_CHECKED.";
            };
            var input=new LinkedHashMap<>(props);input.put("message",string("Brief user-facing reply from the assistant. Describe this local workspace operation; never claim a DB save. The Tool returns directly to the client, with no further model call."));
            out.add(new AiConversationService.Tool(name,description,schema(input,required),"{prepared:true,databaseChanged:false,action,arguments,answer}; returnDirect=true: client renders immediately. No further model call, no DB write."));
        });return List.copyOf(out);
    }
    public static void validate(String name,JsonNode args){String action=ACTIONS.get(name);if(action==null)throw new IllegalArgumentException("CONVERSATION_UNREGISTERED_TOOL");AiConversationProtocol.validateAction(action,args);}
}
