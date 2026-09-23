package com.dbcompanion.model;

import java.util.List;
import com.dbcompanion.service.SqlObjectName;

/** Public metadata only: no authentication payload or secret fields. */
public final class CredentialCatalog {
    private CredentialCatalog() {}
    public record Entry(String owner,String name,String enabled,String usernameFormat,String commentsFormat) {}
    public record Catalog(List<Entry> items,String source,String status,String error,String observedAt) {
        public Catalog { items=List.copyOf(items); }
        public boolean cacheable(){return stable(status);}
    }
    public record Use(String owner,String name,String reference) {}
    public record Section(String kind,List<Use> items,String source,String status,String error) {
        public Section {items=List.copyOf(items);}
    }
    public record Detail(Entry credential,List<Section> sections) {
        public Detail {sections=List.copyOf(sections);}
        public boolean cacheable(){return sections.stream().allMatch(s->stable(s.status()));}
    }
    private static boolean stable(String status){return List.of("AVAILABLE","ACCESS_REQUIRED","UNSUPPORTED").contains(status);}
    public static boolean matches(String value,String defaultOwner,String owner,String name){
        try {
            var parts=SqlObjectName.parts(value);
            return parts.size()==1 ? defaultOwner.equals(owner)&&parts.getFirst().equals(name)
                    : parts.size()==2 && parts.getFirst().equals(owner)&&parts.get(1).equals(name);
        } catch(IllegalArgumentException ex){return false;}
    }
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status,String message){super(message);this.status=status;}
        public int status(){return status;}
    }
}
