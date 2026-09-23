package com.dbcompanion.model;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Current catalog observations, never a captured LLM request or execution authority. */
public final class SelectAiInspection {
    private SelectAiInspection() {}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public static final int MAX_CHARS=300_000;
    private static final Set<String> SETTINGS=Set.of("object_list","object_list_mode","comments","annotations","constraints","enforce_object_list","feedback","feedback_enabled");
    public record ObjectRef(String owner,String name) {}
    public record Table(String owner,String name,String type,String comment,List<ColumnInfo> columns,
                        List<AnnotationInfo> annotations,String annotationStatus,String error,Instant checkedAt) {
        public Table { columns=List.copyOf(columns);annotations=List.copyOf(annotations); }
    }
    public record Feedback(String search,int page,AiFeedback.Page rows,boolean missingTable,String error,Instant checkedAt) {}
    public record FeedbackDetail(String id,AiFeedback.Detail detail,Instant checkedAt) {}
    public record Snapshot(String id,AiAssistant.Profile profile,String question,Instant checkedAt,
                           Map<String,String> settings,List<ObjectRef> objects,List<Table> tables,
                           Feedback feedback,List<FeedbackDetail> feedbackDetails) {
        public Snapshot { settings=Collections.unmodifiableMap(new LinkedHashMap<>(settings));objects=List.copyOf(objects);tables=List.copyOf(tables);feedbackDetails=List.copyOf(feedbackDetails); }
    }
    public static Snapshot create(AiAssistant.Profile profile,String question,List<AiProfileAttribute> attributes,Instant now){
        SelectAiTest.question(question);
        var settings=new LinkedHashMap<String,String>();
        for(var a:attributes)if(SETTINGS.contains(a.name()))settings.put(a.name(),Objects.toString(a.value(),""));
        var refs=new LinkedHashSet<ObjectRef>();String raw=settings.get("object_list");
        if(raw!=null&&!raw.isBlank()){
            try{
                var root=JSON.readTree(raw);if(!root.isArray()||root.size()>1000)throw new IllegalArgumentException();
                for(var entry:root){
                    if(!entry.isObject()||!entry.path("owner").isString()||entry.path("owner").asString().isBlank())throw new IllegalArgumentException();
                    if(entry.has("name")&&!entry.path("name").isNull()&&!entry.path("name").isString())throw new IllegalArgumentException();
                    String owner=entry.path("owner").asString();
                    String name=entry.has("name")&&!entry.path("name").isNull()?entry.path("name").asString():null;
                    if(owner.length()>128||owner.indexOf('\0')>=0||name!=null&&(name.isBlank()||name.length()>128||name.indexOf('\0')>=0))throw new IllegalArgumentException();
                    refs.add(new ObjectRef(owner,name));
                }
            }catch(RuntimeException ex){throw new AiAssistant.Failure(422,"aitest.inspectObjectList","object_list 형식을 해석하지 못했습니다. 프로필 설정 원문을 확인해 주세요.");}
        }
        return bounded(new Snapshot(UUID.randomUUID().toString(),profile,question,now,settings,List.copyOf(refs),List.of(),null,List.of()));
    }
    public static boolean enabled(Snapshot s,String option){return "true".equalsIgnoreCase(Objects.toString(s.settings().get(option),"").strip().replace("\"",""));}
    public static boolean matches(Snapshot s,SelectAiTest.Outcome prompt){return s!=null&&prompt!=null&&s.profile().equals(prompt.profile())&&s.question().equals(prompt.question());}
    public static void requireObject(Snapshot s,String owner,String name){
        if(owner==null||name==null||name.isBlank()||name.length()>128||name.indexOf('\0')>=0||s.objects().stream().noneMatch(o->o.owner().equals(owner)&&(o.name()==null||o.name().equals(name))))
            throw new AiAssistant.Failure(403,"aitest.inspectScope","이 참고정보의 object_list에 허용된 객체만 조회할 수 있습니다.");
    }
    public static Snapshot table(Snapshot s,Table table){
        requireObject(s,table.owner(),table.name());
        var rows=new ArrayList<>(s.tables());rows.removeIf(t->t.owner().equals(table.owner())&&t.name().equals(table.name()));rows.add(table);
        if(rows.size()>40)throw tooLarge();
        return bounded(new Snapshot(s.id(),s.profile(),s.question(),s.checkedAt(),s.settings(),s.objects(),rows,s.feedback(),s.feedbackDetails()));
    }
    public static Snapshot feedback(Snapshot s,Feedback feedback){return bounded(new Snapshot(s.id(),s.profile(),s.question(),s.checkedAt(),s.settings(),s.objects(),s.tables(),feedback,List.of()));}
    public static Snapshot detail(Snapshot s,FeedbackDetail detail){
        if(s.feedback()==null||s.feedback().rows()==null||s.feedback().rows().items().stream().noneMatch(i->i.id().equals(detail.id())))throw stale();
        var details=new ArrayList<>(s.feedbackDetails());details.removeIf(d->d.id().equals(detail.id()));details.add(detail);
        return bounded(new Snapshot(s.id(),s.profile(),s.question(),s.checkedAt(),s.settings(),s.objects(),s.tables(),s.feedback(),details));
    }
    public static Snapshot bounded(Snapshot s){if(JSON.writeValueAsString(s).length()>MAX_CHARS)throw tooLarge();return s;}
    public static AiAssistant.Failure stale(){return new AiAssistant.Failure(409,"aitest.inspectStale","참고정보의 프로필 또는 조회 상태가 변경되었습니다. 다시 조회해 주세요.");}
    private static AiAssistant.Failure tooLarge(){return new AiAssistant.Failure(413,"aitest.inspectTooLong","참고정보 보관 한도를 초과했습니다. 원문은 자르지 않습니다. 더 작은 범위로 다시 조회해 주세요.");}
}
