package com.dbcompanion.model;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Compares only recorded settings; never infers a failure cause or a successful recovery. */
public final class SetupHistoryReview {
    private SetupHistoryReview() {}
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> KEYS = Set.of("object_list", "object_list_mode", "comments", "annotations",
            "constraints", "enforce_object_list", "feedback", "feedback_enabled");
    public record Saved(String id, String parentId, String at, String kind, String model,
                        boolean errorRecorded, String snapshot, String profileVersion) {
        public Saved(String id,String parentId,String at,String kind,String model,boolean errorRecorded,String snapshot){
            this(id,parentId,at,kind,model,errorRecorded,snapshot,null);
        }
    }
    public record Recovery(String id, String retestId) {}
    public record Review(String id, String at, String comparison, List<String> compared, List<String> changed,
                         boolean errorRecorded, Recovery recovery, String versionState) {
        public Review { compared=List.copyOf(compared); changed=List.copyOf(changed); }
        public Review(String id,String at,String comparison,List<String> compared,List<String> changed,boolean errorRecorded,Recovery recovery){
            this(id,at,comparison,compared,changed,errorRecorded,recovery,"UNAVAILABLE");
        }
    }
    public record Result(String state, List<Review> items) {
        public Result { items=List.copyOf(items); }
    }
    public static Review withVersion(Review review,Saved saved,String owner,String profile,String current){
        String state="UNAVAILABLE";
        if("USER_SELECTED".equals(saved.kind())&&saved.profileVersion()!=null){
            try{state=JSON.readValue(saved.profileVersion(),SetupProfileVersion.class).compare(owner,profile,current);}
            catch(RuntimeException ex){state="UNAVAILABLE";}
        }
        return new Review(review.id(),review.at(),review.comparison(),review.compared(),review.changed(),review.errorRecorded(),review.recovery(),state);
    }
    public static Review compare(Saved saved, List<AiProfileAttribute> current, Recovery recovery) {
        if (!"USER_SELECTED".equals(saved.kind())) return unknown(saved,"MANUAL",recovery);
        try {
            if(saved.snapshot()==null || saved.snapshot().isBlank()) return unknown(saved,"UNAVAILABLE",recovery);
            var snapshot=JSON.readValue(saved.snapshot(),ProblemQuestion.Snapshot.class);
            if(snapshot.checkedAt()==null || saved.at()==null) return unknown(saved,"UNAVAILABLE",recovery);
            if(snapshot.checkedAt().isAfter(Instant.parse(saved.at())) || "POST_GENERATION_READ".equals(snapshot.availability()))
                return unknown(saved,"AFTER",recovery);
            if(!Set.of("PRE_GENERATION_READ","CAPTURED").contains(snapshot.availability())) return unknown(saved,"UNAVAILABLE",recovery);
            var values=JSON.readTree(snapshot.value());
            if(!values.isObject()) return unknown(saved,"UNAVAILABLE",recovery);
            Map<String,String> now=new HashMap<>();
            current.forEach(a->now.put(a.name(),a.value()));
            var compared=new ArrayList<String>();var changed=new ArrayList<String>();
            for(String key:KEYS.stream().sorted().toList()) {
                if(!values.has(key))continue;
                if(!values.path(key).isString())return unknown(saved,"UNAVAILABLE",recovery);
                String old=values.path(key).asString();
                if(old.contains("[REDACTED"))return unknown(saved,"UNAVAILABLE",recovery);
                compared.add(key);
                if(!same(old,now.get(key)))changed.add(key);
            }
            if(saved.model()!=null&&!saved.model().isBlank()) {
                compared.add("model");if(!same(saved.model(),now.get("model")))changed.add("model");
            }
            if(compared.isEmpty())return unknown(saved,"UNAVAILABLE",recovery);
            return new Review(saved.id(),saved.at(),changed.isEmpty()?"SAME_FIELDS":"DIFFERENT",compared,changed,saved.errorRecorded(),recovery);
        } catch(RuntimeException ex) { return unknown(saved,"UNAVAILABLE",recovery); }
    }
    private static boolean same(String old,String current) {
        if(Objects.equals(old,current))return true;
        if(old==null||current==null)return false;
        try {return JSON.readTree(old).equals(JSON.readTree(current));}
        catch(RuntimeException ex){return old.strip().equals(current.strip());}
    }
    private static Review unknown(Saved saved,String reason,Recovery recovery) {
        return new Review(saved.id(),saved.at(),reason,List.of(),List.of(),saved.errorRecorded(),recovery);
    }
}
