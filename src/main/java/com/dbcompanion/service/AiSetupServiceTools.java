package com.dbcompanion.service;

import java.util.*;
import java.util.function.BiFunction;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.function.FunctionToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** In-process adapters: the server supplies the invocation, never the model. */
public final class AiSetupServiceTools {
    private static final JsonMapper JSON=new JsonMapper();
    private static final Set<String> REGISTERED=Set.of("setup.inspectConfiguration","setup.inspectHistory","profile.inspectOptions",
            "glossary.inspectStorage","semantic.listSources","semantic.previewAttachment");
    private AiSetupServiceTools() {}
    public record Contract(String name,String description,String useWhen,Map<String,Object> inputSchema,
            Map<String,Object> outputSchema,Map<String,Object> example,String renderer) {}
    public record Step(String id,String purpose,List<String> services,String completion) {}
    public record Catalogue(int version,List<Contract> services,List<Step> workflow) {}
    private static final Catalogue CATALOGUE=load();
    private static Catalogue load(){
        try(var input=AiSetupServiceTools.class.getResourceAsStream("/setup/service-tools.json")){
            var catalogue=JSON.readValue(Objects.requireNonNull(input),Catalogue.class);
            var names=new HashSet<String>();
            for(var contract:catalogue.services()){
                if(!names.add(contract.name())||contract.description().isBlank()||contract.inputSchema().isEmpty()
                        ||contract.outputSchema().isEmpty()||!contract.renderer().equals("json"))throw new IllegalStateException("Invalid service contract");
            }
            if(!names.equals(REGISTERED)||catalogue.workflow().stream().anyMatch(step->!names.containsAll(step.services())))
                throw new IllegalStateException("Service contract and adapter mismatch");
            return catalogue;
        }catch(java.io.IOException ex){throw new IllegalStateException("Service catalogue unavailable",ex);}
    }
    public static Catalogue catalogue(){return CATALOGUE;}
    public static Contract contract(String name){return CATALOGUE.services().stream().filter(c->c.name().equals(name)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Unregistered service tool"));}
    public static void validate(String name,JsonNode arguments){
        contract(name);
        if(!arguments.isObject())throw new IllegalArgumentException("INVALID_TOOL_ARGUMENTS");
        if(name.equals("semantic.previewAttachment")){
            if(arguments.size()!=1||!arguments.path("source").isString()
                    ||!arguments.path("source").asString().matches("[A-Z][A-Z0-9_$#]{0,124}"))throw new IllegalArgumentException("INVALID_TOOL_ARGUMENTS");
        }else if(!arguments.isEmpty())throw new IllegalArgumentException("INVALID_TOOL_ARGUMENTS");
    }
    public static Object invoke(String name,JsonNode arguments,BiFunction<String,JsonNode,Object> server){
        validate(name,arguments);
        var contract=contract(name);
        return invokeRegistered(name,contract.description(),contract.inputSchema(),arguments,server);
    }
    /** Callers must resolve the name from their server-owned registry and validate arguments. */
    public static Object invokeRegistered(String name,String description,Map<String,Object> inputSchema,JsonNode arguments,BiFunction<String,JsonNode,Object> server){
        // The callback and context are created by the server for this invocation only.
        var callback=FunctionToolCallback.<Map<String,Object>,Object>builder(name,(input,context)->{
            @SuppressWarnings("unchecked")
            var target=(BiFunction<String,JsonNode,Object>)context.getContext().get("invocation");
            return target.apply(name,JSON.valueToTree(input));
        }).description(description).inputType(new org.springframework.core.ParameterizedTypeReference<Map<String,Object>>() {})
                .inputSchema(JSON.writeValueAsString(inputSchema))
                .toolCallResultConverter((result,type)->JSON.writeValueAsString(result)).build();
        try{return JSON.readTree(callback.call(JSON.writeValueAsString(arguments),new ToolContext(Map.of("invocation",server))));}
        catch(org.springframework.ai.tool.execution.ToolExecutionException ex){
            if(ex.getCause() instanceof RuntimeException cause)throw cause;
            throw ex;
        }
    }
}
