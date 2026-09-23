package com.dbcompanion.model;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Login-scoped display snapshots, never scheduler execution state owned by this app. */
public final class Scheduler {
    private Scheduler() {}
    public static boolean stable(String status){return Set.of("AVAILABLE","ACCESS_REQUIRED","UNSUPPORTED","NOT_FOUND","NOT_APPLICABLE","NOT_EXPANDED","UNRESOLVED").contains(status);}
    public record Rows(List<Map<String,String>> items,String source,String status,String error,String checkedAt){
        public Rows {items=items.stream().map(row->Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();}
        public boolean cacheable(){return stable(status);}
        public Map<String,String> first(){return items.isEmpty()?Map.of():items.getFirst();}
        public static Rows empty(String status){return new Rows(List.of(),"",status,"",Instant.now().toString());}
    }
    public record Detail(Rows job,Rows program,Rows schedule,Rows arguments,Rows programArguments){
        public boolean cacheable(){return List.of(job,program,schedule,arguments,programArguments).stream().allMatch(Rows::cacheable);}
    }
    public record Action(String owner,String type,String text,String status){}
    public static Action action(String schema,Detail detail){
        if(!detail.job().status().equals("AVAILABLE"))return new Action(schema,"","",detail.job().status());
        Map<String,String> job=detail.job().first();
        if(job.isEmpty())return new Action(schema,"","","NOT_FOUND");
        if(present(job.get("PROGRAM_NAME"))){
            if(!detail.program().status().equals("AVAILABLE"))return new Action(schema,"","",detail.program().status());
            Map<String,String> program=detail.program().first();
            if(program.isEmpty())return new Action(schema,"","","NOT_FOUND");
            return new Action(Objects.toString(job.get("PROGRAM_OWNER"),schema),program.get("PROGRAM_TYPE"),program.get("PROGRAM_ACTION"),"AVAILABLE");
        }
        return new Action(schema,job.get("JOB_TYPE"),job.get("JOB_ACTION"),"AVAILABLE");
    }
    public static boolean present(String value){return value!=null&&!value.isBlank();}
    public record Code(Action action,List<RoutineSource.Definition> definitions,String status,String error,String checkedAt){
        public Code {definitions=List.copyOf(definitions);}
        public boolean cacheable(){return stable(status);}
    }
    public record Page(Rows rows,String next){
        public static Page from(Rows data){
            boolean more=data.items().size()>10;
            var items=data.items().stream().limit(10).toList();
            return new Page(new Rows(items,data.source(),data.status(),data.error(),data.checkedAt()),more?items.getLast().get("LOG_ID"):"");
        }
    }
    public static void name(String value){if(value==null||value.isBlank()||value.length()>128||value.indexOf(0)>=0)throw new IllegalArgumentException("Invalid job name");}
    public static void cursor(String value){if(value==null||!value.isEmpty()&&!value.matches("[1-9][0-9]{0,37}"))throw new IllegalArgumentException("Invalid log cursor");}
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status,String message){super(message);this.status=status;}
        public int status(){return status;}
    }
    public static final class State {
        private record Key(String schema,String job){}
        private record LogKey(Key job,String id){}
        private final Map<String,Rows> lists=new HashMap<>();
        private final Map<Key,Detail> details=new HashMap<>();
        private final Map<Key,Code> codes=new HashMap<>();
        private final Map<LogKey,Page> pages=new HashMap<>();
        private final Map<LogKey,Rows> logs=new HashMap<>();
        public synchronized Rows list(String schema,boolean refresh,Supplier<Rows> loader){
            if(refresh){lists.remove(schema);details.keySet().removeIf(k->k.schema().equals(schema));codes.keySet().removeIf(k->k.schema().equals(schema));pages.keySet().removeIf(k->k.job().schema().equals(schema));logs.keySet().removeIf(k->k.job().schema().equals(schema));}
            if(lists.containsKey(schema))return lists.get(schema);
            var data=Objects.requireNonNull(loader.get());if(data.cacheable())lists.put(schema,data);return data;
        }
        public synchronized Detail detail(String schema,String job,Supplier<Detail> loader){
            var key=new Key(schema,job);if(details.containsKey(key))return details.get(key);
            var data=Objects.requireNonNull(loader.get());if(data.cacheable())details.put(key,data);return data;
        }
        public synchronized Code code(String schema,String job,Supplier<Code> loader){
            var key=new Key(schema,job);if(codes.containsKey(key))return codes.get(key);
            var data=Objects.requireNonNull(loader.get());if(data.cacheable())codes.put(key,data);return data;
        }
        public synchronized Page page(String schema,String job,String before,Supplier<Page> loader){
            cursor(before);var parent=new Key(schema,job);var key=new LogKey(parent,before);
            if(pages.containsKey(key))return pages.get(key);
            if(!before.isEmpty()&&pages.entrySet().stream().noneMatch(e->e.getKey().job().equals(parent)&&e.getValue().next().equals(before)))throw new IllegalArgumentException("Unknown log cursor");
            var data=Objects.requireNonNull(loader.get());if(data.rows().cacheable())pages.put(key,data);return data;
        }
        public synchronized Rows log(String schema,String job,String id,Supplier<Rows> loader){
            cursor(id);var parent=new Key(schema,job);var key=new LogKey(parent,id);
            if(id.isEmpty()||pages.entrySet().stream().filter(e->e.getKey().job().equals(parent)).flatMap(e->e.getValue().rows().items().stream()).noneMatch(row->id.equals(row.get("LOG_ID"))))throw new IllegalArgumentException("Unknown log entry");
            if(logs.containsKey(key))return logs.get(key);
            var data=Objects.requireNonNull(loader.get());if(data.cacheable())logs.put(key,data);return data;
        }
    }
}
