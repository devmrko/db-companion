package com.dbcompanion.model;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Login-scoped registration confirmation and bounded public metadata cache. */
public final class CatalogOperations {
    private CatalogOperations() {}
    public record Link(String owner,String name,String fingerprint) {}
    public record Preview(String token,String catalog,String owner,String link,String sql) {}
    public record Prepared(Preview view,Link link,String api,Instant expires) {}
    public record Receipt(String catalog,String status,String error) {}
    public enum Level { schemas, tables, columns }
    public record Key(String catalog,Level level,String schema,String table) {}
    public record Grid(List<String> fields,List<Map<String,String>> rows) {
        public Grid {
            fields=List.copyOf(fields);
            rows=rows.stream().map(row->Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        }
        public boolean contains(String field,String value){return rows.stream().anyMatch(row->Objects.equals(row.get(field),value));}
    }
    public static String catalogName(String value){
        if(value==null)throw new IllegalArgumentException("Missing catalog name");
        String name=value.strip().toUpperCase(Locale.ROOT);
        if(!name.matches("[A-Z][A-Z0-9_]{0,127}")||name.equals("LOCAL"))throw new IllegalArgumentException("Invalid catalog name");
        return name;
    }
    public static String identifier(String name){
        if(name==null||name.isBlank()||name.length()>128||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid identifier");
        return name;
    }
    /** Catalog column APIs parse SQL identifiers; keep remote case rather than normalizing it. */
    public static String apiIdentifier(String name){return "\""+identifier(name).replace("\"","\"\"")+"\"";}
    public static boolean enabled(String value){return value!=null&&Set.of("YES","Y","TRUE","1","ENABLED").contains(value.toUpperCase(Locale.ROOT));}
    public static boolean accessibleOwner(String user,String owner){return user.equals(owner)||"PUBLIC".equals(owner);}
    public static Link chooseLink(List<Link> links,String user,String owner,String name){
        if(!accessibleOwner(user,owner))throw new IllegalArgumentException("Inaccessible link owner");
        // An own private link shadows a public link with the same name; never mount the wrong target.
        String actualOwner=links.stream().anyMatch(l->l.name().equals(name)&&l.owner().equals(user))?user:"PUBLIC";
        if(!actualOwner.equals(owner))throw new IllegalArgumentException("Shadowed public link");
        return links.stream().filter(l->l.owner().equals(owner)&&l.name().equals(name)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Link no longer available"));
    }
    public static final class State {
        private Prepared prepared;
        private final Map<Key,Grid> grids=new LinkedHashMap<>(32,.75f,true);
        public synchronized void prepare(Prepared value){prepared=value;}
        public synchronized Prepared consume(String token,Instant now){
            if(prepared==null||token==null||!prepared.view().token().equals(token))throw new IllegalArgumentException("Invalid confirmation");
            Prepared result=prepared;prepared=null;
            if(!now.isBefore(result.expires()))throw new IllegalArgumentException("Expired confirmation");
            return result;
        }
        public synchronized Grid grid(Key key,Supplier<Grid> loader){
            if(grids.containsKey(key))return grids.get(key);
            Grid result=Objects.requireNonNull(loader.get());grids.put(key,result);
            while(grids.size()>24)grids.remove(grids.keySet().iterator().next());
            return result;
        }
        public synchronized void clear(){grids.clear();prepared=null;}
    }
}
