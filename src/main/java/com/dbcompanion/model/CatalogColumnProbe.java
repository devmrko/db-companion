package com.dbcompanion.model;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Temporary, fixed-target metadata diagnostics. Never accepts SQL from a request. */
public final class CatalogColumnProbe {
    private CatalogColumnProbe() {}
    public enum Step { PARENT_TYPE, QUOTED_NAMES, LINK_DICTIONARY, LINK_COMMENTS, MYSQL_COLUMNS, MYSQL_TABLES, MYSQL_PASSTHROUGH, MYSQL_COMMENT_LENGTHS }
    public record Target(String login,String catalog,String schema,String table,String link) {
        public Target {
            for(String value:List.of(login,catalog,schema,table,link))CatalogOperations.identifier(value);
            if(!link.matches("[A-Za-z][A-Za-z0-9_$#]*(\\.[A-Za-z][A-Za-z0-9_$#]*)*"))
                throw new IllegalArgumentException("Invalid database link name");
        }
        public boolean allowed(String user){return login.equals(user);}
    }
    public record Result(Step step,Instant time,String sql,List<String> binds,CatalogOperations.Grid grid,String error) {
        public Result { binds=List.copyOf(binds); }
    }
    public static String quoted(String value){return CatalogOperations.apiIdentifier(value);}
    public static final class State {
        private final Map<Step,Result> results=new EnumMap<>(Step.class);
        public synchronized Result once(Step step,Supplier<Result> work){return results.computeIfAbsent(step,key->Objects.requireNonNull(work.get()));}
        public synchronized List<Result> results(){return List.copyOf(results.values());}
    }
}
