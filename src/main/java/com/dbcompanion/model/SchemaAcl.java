package com.dbcompanion.model;

import com.dbcompanion.model.ExternalSources.*;
import java.util.*;

/** Grant-path presentation only. This does not evaluate effective network access. */
public final class SchemaAcl {
    private SchemaAcl() {}
    public record Grant(String grantee,String role,String defaultRole) {}
    public record Roles(List<Grant> items,String status,String error) {
        public Roles { items=List.copyOf(items); }
    }
    public record Row(String schema,Ace ace,String route,List<String> path,String defaultRole) {
        public Row { path=List.copyOf(path); }
    }
    public record Catalog(String schema,List<Row> items,List<Ace> review,String source,String status,String error,String roleStatus) {
        public Catalog { items=List.copyOf(items);review=List.copyOf(review); }
        public boolean cacheable(){
            return Set.of("AVAILABLE","ACCESS_REQUIRED","UNSUPPORTED","MAPPING_UNAVAILABLE").contains(status)
                ||status.equals("PARTIAL")&&Set.of("AVAILABLE","ACCESS_REQUIRED","UNSUPPORTED").contains(roleStatus);
        }
    }
    private record Path(List<String> names,String defaultRole) {}
    public static Catalog map(String schema,AclCatalog acl,Roles roles){
        if(!acl.status().equals("AVAILABLE"))return new Catalog(schema,List.of(),List.of(),acl.source(),acl.status(),acl.error(),"NOT_READ");
        if(!acl.scope().equals("DATABASE"))return new Catalog(schema,List.of(),List.of(),acl.source(),"MAPPING_UNAVAILABLE","","NOT_READ");
        Map<String,Path> user=paths(schema,roles.items()),common=paths("PUBLIC",roles.items());
        var rows=new ArrayList<Row>();var review=new ArrayList<Ace>();
        for(Ace ace:acl.items()){
            if(!"DATABASE".equalsIgnoreCase(ace.principalType())||!"NO".equalsIgnoreCase(ace.invertedPrincipal())||ace.principal()==null){review.add(ace);continue;}
            String principal=ace.principal();
            if(principal.equals(schema))rows.add(new Row(schema,ace,"DIRECT",List.of(schema),""));
            if(principal.equals("PUBLIC"))rows.add(new Row(schema,ace,"PUBLIC",List.of("PUBLIC"),""));
            if(roles.status().equals("AVAILABLE")){
                if(user.containsKey(principal))rows.add(new Row(schema,ace,"ROLE",user.get(principal).names(),user.get(principal).defaultRole()));
                if(common.containsKey(principal))rows.add(new Row(schema,ace,"PUBLIC_ROLE",common.get(principal).names(),""));
            }
        }
        String status=roles.status().equals("AVAILABLE")&&review.isEmpty()?"AVAILABLE":"PARTIAL";
        return new Catalog(schema,rows,review,acl.source()+" + SYS.DBA_ROLE_PRIVS",status,roles.error(),roles.status());
    }
    private static Map<String,Path> paths(String root,List<Grant> grants){
        var edges=new HashMap<String,List<Grant>>();
        for(Grant grant:grants)edges.computeIfAbsent(grant.grantee(),key->new ArrayList<>()).add(grant);
        edges.values().forEach(list->list.sort(Comparator.comparing(Grant::role).thenComparing(g->Objects.toString(g.defaultRole(),""))));
        var result=new LinkedHashMap<String,Path>();var queue=new ArrayDeque<Path>();var visited=new HashSet<String>();
        visited.add(root);queue.add(new Path(List.of(root),""));
        while(!queue.isEmpty()){
            var previous=queue.remove();String parent=previous.names().getLast();
            for(Grant edge:edges.getOrDefault(parent,List.of())){
                if(!visited.add(edge.role()))continue;
                var names=new ArrayList<>(previous.names());names.add(edge.role());
                var path=new Path(List.copyOf(names),previous.names().size()==1?Objects.toString(edge.defaultRole(),""):previous.defaultRole());
                result.put(edge.role(),path);queue.add(path);
            }
        }
        return result;
    }
    public static final class LimitExceeded extends RuntimeException {}
    /** Bounded breadth-first metadata frontier: each grantee is read at most once. */
    public static final class Walk {
        private final LinkedHashSet<String> pending=new LinkedHashSet<>();
        private final Set<String> visited=new HashSet<>();
        private final Map<String,Integer> depths=new HashMap<>();
        private final Set<Grant> grants=new LinkedHashSet<>();
        public Walk(String schema){pending.add(schema);pending.add("PUBLIC");pending.forEach(name->depths.put(name,0));}
        public List<String> next(){
            var batch=pending.stream().limit(200).toList();pending.removeAll(batch);visited.addAll(batch);return batch;
        }
        public void accept(List<String> batch,List<Grant> result){
            for(Grant grant:result){
                if(!batch.contains(grant.grantee())||grant.role()==null)throw new IllegalArgumentException("Unexpected role metadata");
                grants.add(grant);if(grants.size()>5000)throw new LimitExceeded();
                if(visited.contains(grant.role())||pending.contains(grant.role()))continue;
                int depth=depths.get(grant.grantee())+1;if(depth>32)throw new LimitExceeded();
                pending.add(grant.role());depths.put(grant.role(),depth);
                if(pending.size()+visited.size()>1000)throw new LimitExceeded();
            }
        }
        public List<Grant> grants(){return List.copyOf(grants);}
    }
}
