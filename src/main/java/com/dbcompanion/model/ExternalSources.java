package com.dbcompanion.model;

import java.util.*;
import java.util.function.Supplier;

/** Only public, redacted metadata belongs in this login-scoped cache. */
public final class ExternalSources {
    private ExternalSources() {}
    public enum Kind { links, tables }
    public record Entry(String owner,String name,String description) {}
    public record Ace(String host,String lowerPort,String upperPort,String principal,String principalType,
            String privilege,String grantType,String status,String aceOrder,String startDate,String endDate,String invertedPrincipal) {}
    public record AclCatalog(List<Ace> items,String source,String scope,String status,String error) {
        public AclCatalog { items=List.copyOf(items); }
        public boolean cacheable(){return stable(status);}
    }
    public record Catalog(List<Entry> items,String source,String status,String error) {
        public Catalog { items=List.copyOf(items); }
        public boolean cacheable(){return stable(status);}
    }
    public record Field(String key,String value) {}
    public record Location(String partition,String subpartition,String directory,String location) {}
    public record Section(String kind,List<Location> items,String source,String status,String error) {
        public Section { items=List.copyOf(items); }
    }
    public record Detail(Entry entry,List<Field> fields,List<Section> sections) {
        public Detail { fields=List.copyOf(fields);sections=List.copyOf(sections); }
        public boolean cacheable(){return sections.stream().allMatch(s->stable(s.status()));}
    }
    private static boolean stable(String status){return Set.of("AVAILABLE","ACCESS_REQUIRED","UNSUPPORTED").contains(status);}
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status,String message){super(message);this.status=status;}
        public int status(){return status;}
    }
    public static final class State {
        private final MountedCatalogs.State mounted=new MountedCatalogs.State();
        public MountedCatalogs.State mounted(){return mounted;}
        private record Scope(String schema,Kind kind) {}
        private record Key(Scope scope,String owner,String name) {}
        private final Map<Scope,Catalog> lists=new HashMap<>();
        private final Map<Key,Detail> details=new HashMap<>();
        private AclCatalog acl;
        private final Map<String,SchemaAcl.Catalog> schemaAcls=new HashMap<>();
        private void forgetAcl(){acl=null;schemaAcls.clear();}
        public synchronized AclCatalog acl(boolean refresh,Supplier<AclCatalog> loader){
            if(refresh)forgetAcl();
            if(acl!=null)return acl;
            var value=Objects.requireNonNull(loader.get());if(value.cacheable())acl=value;return value;
        }
        public synchronized SchemaAcl.Catalog schemaAcl(String schema,boolean refresh,Supplier<SchemaAcl.Catalog> loader){
            if(refresh)forgetAcl();
            if(schemaAcls.containsKey(schema))return schemaAcls.get(schema);
            var value=Objects.requireNonNull(loader.get());if(value.cacheable())schemaAcls.put(schema,value);return value;
        }
        public synchronized Catalog list(String schema,Kind kind,boolean refresh,Supplier<Catalog> loader){
            var scope=new Scope(schema,kind);
            if(refresh){lists.remove(scope);details.keySet().removeIf(k->k.scope().equals(scope));}
            if(lists.containsKey(scope))return lists.get(scope);
            var value=Objects.requireNonNull(loader.get());if(value.cacheable())lists.put(scope,value);return value;
        }
        public synchronized Detail detail(String schema,Kind kind,String owner,String name,Supplier<Detail> loader){
            var key=new Key(new Scope(schema,kind),owner,name);
            if(details.containsKey(key))return details.get(key);
            var value=Objects.requireNonNull(loader.get());if(value.cacheable())details.put(key,value);return value;
        }
    }
}
