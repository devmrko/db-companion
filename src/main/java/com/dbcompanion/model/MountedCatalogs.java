package com.dbcompanion.model;

import java.util.*;
import java.util.function.Supplier;

/** Registration metadata only; never remote objects, credentials or arbitrary configuration. */
public final class MountedCatalogs {
    private MountedCatalogs() {}
    public record Entry(String name,String type,String enabled) {}
    public record Api(String status,List<String> methods,String source,String error) {
        public Api { methods=List.copyOf(methods); }
    }
    public record Catalog(List<Entry> items,String source,String status,String error,Api api) {
        public Catalog { items=List.copyOf(items); }
        public boolean cacheable(){return stable(status)&&stable(api.status());}
    }
    public record Field(String key,String value) {}
    public record Detail(Entry entry,List<Field> fields,String source) {
        public Detail { fields=List.copyOf(fields); }
    }
    private static boolean stable(String status){
        return Set.of("AVAILABLE","ACCESS_REQUIRED","UNSUPPORTED","VISIBLE","NOT_VISIBLE").contains(status);
    }
    public static final class State {
        private final CatalogOperations.State operations=new CatalogOperations.State();
        public CatalogOperations.State operations(){return operations;}
        private Catalog catalog;
        private final Map<String,Detail> details=new HashMap<>();
        public synchronized void clear(){catalog=null;details.clear();operations.clear();}
        public synchronized Catalog list(boolean refresh,Supplier<Catalog> loader){
            if(refresh)clear();
            if(catalog!=null)return catalog;
            Catalog value=Objects.requireNonNull(loader.get());if(value.cacheable())catalog=value;return value;
        }
        public synchronized Detail detail(String name,Supplier<Detail> loader){
            if(details.containsKey(name))return details.get(name);
            Detail value=Objects.requireNonNull(loader.get());details.put(name,value);return value;
        }
    }
}
