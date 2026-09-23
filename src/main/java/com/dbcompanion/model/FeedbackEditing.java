package com.dbcompanion.model;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class FeedbackEditing {
    private FeedbackEditing() {}
    public record Form(String token,String sqlText,String response,String feedback,boolean editing,boolean historyReady) {}
    public record Save(String token,String sqlText,String response,String feedback,@com.fasterxml.jackson.annotation.JsonProperty(required=true) boolean confirmed,@com.fasterxml.jackson.annotation.JsonProperty(required=true) boolean consent){
        @com.fasterxml.jackson.annotation.JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unknown Feedback field: "+key);}
    }
    public record Result(boolean verified,String stage,String detail) {}
    public static AiCreation.Feedback desired(Save request,JsonMapper json){
        if(request==null||!request.confirmed()||!request.consent())throw AiCreation.error(400,"consent");
        AiCreation.text(request.sqlText(),AiCreation.MAX_BYTES);AiCreation.text(request.response(),AiCreation.MAX_BYTES);AiCreation.text(request.feedback(),AiCreation.MAX_BYTES);
        if(request.response().isBlank()||!request.sqlText().matches("(?is)^select\\s+ai\\s+showsql\\s+\\S[\\s\\S]*$"))throw error("sqlText");
        String question=request.sqlText().replaceFirst("(?is)^select\\s+ai\\s+showsql\\s+","");
        var attrs=new LinkedHashMap<String,Object>();attrs.put("feedback_type","negative");attrs.put("sql_id",null);attrs.put("sql_text",request.sqlText());attrs.put("response",request.response());attrs.put("feedback_content",request.feedback().isEmpty()?null:request.feedback());
        return new AiCreation.Feedback(question,json.writeValueAsString(attrs));
    }
    public static com.dbcompanion.common.exception.MetadataEditException error(String key){return new com.dbcompanion.common.exception.MetadataEditException(409,key,com.dbcompanion.common.i18n.UiMessages.text("feedbackEdit."+key,key));}
    public static final class Plan {
        public final String token=UUID.randomUUID().toString(),schema,profile,id;public final Instant expires=Instant.now().plusSeconds(600);
        public final Map<String,Object> snapshot;public final AiCreation.Feedback before;public boolean used;
        public Plan(String schema,String profile,String id,Map<String,Object> snapshot,AiCreation.Feedback before){this.schema=schema;this.profile=profile;this.id=id;this.snapshot=Map.copyOf(snapshot);this.before=before;}
        public void take(String token){if(used||!this.token.equals(token)||!Instant.now().isBefore(expires))throw AiCreation.error(409,"expired");used=true;}
    }
    public static final class State {
        private Plan plan;
        public void set(Plan value){plan=value;}
        public void clear(){plan=null;}
        public Plan take(String token){if(plan==null)throw AiCreation.error(409,"expired");plan.take(token);return plan;}
    }
}
