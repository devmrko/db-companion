package com.dbcompanion.model;

import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyRelations.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Deterministic DDL from verified column mappings, never LLM SQL. */
public final class PropertyGraph {
    private PropertyGraph(){}
    public record Item(String kind,String name,String alias,String status,String reason){}
    public record Definition(String schema,String name,String sql,List<Item> items,int vertices,int edges){public Definition{items=List.copyOf(items);}}
    public record Access(boolean supported,boolean owner,boolean privilege,boolean exists,String existingStatus){public boolean allowed(){return supported&&owner&&privilege&&!exists;}}
    public record Preview(String token,Definition definition,Access access,Instant expires,boolean canCreate){}
    public record Draft(String token,String schema,Definition definition,List<Entry> entries,Instant expires){public Draft{entries=List.copyOf(entries);}}
    public record Created(String schema,String name,String status,String query){}
    public static String quote(String name){Ontology.name(name);return "\""+name.replace("\"","\"\"")+"\"";}
    public static String graphName(String value){if(value==null||!value.matches("[A-Za-z][A-Za-z0-9_]{0,59}"))throw new Failure(400,"pg.nameInvalid");return value.toUpperCase(Locale.ROOT);}
    private static String cols(List<String> names){return names.stream().map(PropertyGraph::quote).collect(Collectors.joining(", "));}
    private static Map<String,ColumnInfo> columns(Entry entry){var result=new HashMap<String,ColumnInfo>();entry.document().source().columns().forEach(c->result.put(c.name(),c));return result;}
    private static boolean keyType(String value){return value!=null&&value.toUpperCase(Locale.ROOT).matches("(?:NUMBER|FLOAT|VARCHAR2|NVARCHAR2|CHAR|NCHAR|BINARY_FLOAT|BINARY_DOUBLE|DATE|TIMESTAMP)(?:\\([^)]*\\))?");}
    private static boolean unique(Key k,Map<String,ColumnInfo> columns){return Set.of("P","U").contains(k.type())&&"ENABLED".equals(k.status())&&"VALIDATED".equals(k.validated())&&!k.columns().isEmpty()&&k.columns().size()<=32&&k.columns().stream().allMatch(n->{var c=columns.get(n);return c!=null&&"N".equals(c.nullable())&&keyType(c.dataType());});}
    private static List<String> vertexKey(Entry entry){var fields=columns(entry);return entry.document().source().keys().stream().filter(k->unique(k,fields)).sorted(Comparator.comparing((Key k)->!k.type().equals("P")).thenComparing(Key::name)).map(Key::columns).findFirst().orElse(List.of());}
    public static Definition build(String schema,String requested,List<Entry> entries,Analysis analysis){
        String name=graphName(requested);if(!analysis.schema().equals(schema))throw new Failure(409,"stale");
        var tables=new LinkedHashMap<String,Entry>();entries.forEach(e->{if(!e.document().source().schema().equals(schema)||tables.putIfAbsent(e.document().source().table(),e)!=null)throw new Failure(409,"stale");});
        var keys=new LinkedHashMap<String,List<String>>();var aliases=new HashMap<String,String>();var items=new ArrayList<Item>();var vertices=new ArrayList<String>();var edges=new ArrayList<String>();
        for(var table:tables.entrySet()){
            var key=vertexKey(table.getValue());String alias="V"+(vertices.size()+1);
            if(key.isEmpty()){items.add(new Item("TABLE",table.getKey(),"","EXCLUDED","KEY_REQUIRED"));continue;}
            aliases.put(table.getKey(),alias);keys.put(table.getKey(),key);
            vertices.add(quote(schema)+"."+quote(table.getKey())+" AS "+quote(alias)+" KEY ("+cols(key)+") LABEL "+quote(table.getKey())+" NO PROPERTIES");
            items.add(new Item("TABLE",table.getKey(),alias,"INCLUDED",""));
        }
        for(var r:analysis.relations()){
            String display=r.source()+" → "+r.targetSchema()+"."+r.target()+(r.label().isBlank()?"":" · "+r.label());String reason="";
            if(!Set.of("FK","APPROVED").contains(r.status()))reason="UNCONFIRMED";
            else if(r.status().equals("FK")&&(!"ENABLED".equals(r.key().status())||!"VALIDATED".equals(r.key().validated())))reason="UNCONFIRMED";
            else if(!schema.equals(r.targetSchema())||!keys.containsKey(r.source())||!keys.containsKey(r.target()))reason="KEY_REQUIRED";
            else if(!r.condition().isBlank())reason="CONDITION";
            else{
                var target=tables.get(r.target());var fields=columns(target);var from=columns(tables.get(r.source()));
                if(r.sourceColumns().isEmpty()||r.sourceColumns().size()!=r.targetColumns().size()||new HashSet<>(r.sourceColumns()).size()!=r.sourceColumns().size()||new HashSet<>(r.targetColumns()).size()!=r.targetColumns().size())reason="MAPPING";
                else if(target.document().source().keys().stream().noneMatch(k->unique(k,fields)&&new HashSet<>(k.columns()).equals(new HashSet<>(r.targetColumns()))))reason="TARGET_KEY";
                else for(int i=0;i<r.sourceColumns().size();i++){var a=from.get(r.sourceColumns().get(i));var b=fields.get(r.targetColumns().get(i));if(a==null||b==null||!OntologyRelations.compatible(a.dataType(),b.dataType()))reason="MAPPING";}
            }
            if(!reason.isEmpty()){items.add(new Item("RELATION",display,"","EXCLUDED",reason));continue;}
            String alias="E"+(edges.size()+1);
            edges.add(quote(schema)+"."+quote(r.source())+" AS "+quote(alias)+" KEY ("+cols(keys.get(r.source()))+")\n    SOURCE KEY ("+cols(keys.get(r.source()))+") REFERENCES "+quote(aliases.get(r.source()))+" ("+cols(keys.get(r.source()))+")\n    DESTINATION KEY ("+cols(r.sourceColumns())+") REFERENCES "+quote(aliases.get(r.target()))+" ("+cols(r.targetColumns())+") LABEL "+quote(alias)+" NO PROPERTIES");
            items.add(new Item("RELATION",display,alias,"INCLUDED",""));
        }
        String sql=vertices.isEmpty()?"":"CREATE PROPERTY GRAPH "+quote(schema)+"."+quote(name)+"\nVERTEX TABLES (\n  "+String.join(",\n  ",vertices)+"\n)"+(edges.isEmpty()?"":"\nEDGE TABLES (\n  "+String.join(",\n  ",edges)+"\n)")+"\nOPTIONS (TRUSTED MODE)";
        if(sql.length()>500_000)throw new Failure(413,"limit");return new Definition(schema,name,sql,items,vertices.size(),edges.size());
    }
    public static boolean sameDefinitions(List<Entry> before,List<Entry> after){
        if(before.size()!=after.size())return false;var refs=new HashMap<String,Entry>();before.forEach(e->refs.put(e.document().source().table(),e));
        return after.stream().allMatch(e->{var old=refs.get(e.document().source().table());return old!=null&&old.documentId().equals(e.documentId())&&old.revision()==e.revision()&&old.document().equals(e.document());});
    }
}
