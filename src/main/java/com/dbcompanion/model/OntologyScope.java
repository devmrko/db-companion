package com.dbcompanion.model;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** A saved metadata selection, not a Select AI profile or an authorization boundary. */
public final class OntologyScope {
    private OntologyScope(){}
    public static final String TYPE="ONTOLOGY_SCOPE";
    public record Selection(int format,String database,String schema,String name,List<String> tables){
        public Selection {tables=names(tables);Ontology.name(schema);Ontology.name(database);
            if(format!=1||name==null||name.isBlank()||name.length()>100||name.codePoints().anyMatch(Character::isISOControl))throw new Ontology.Failure(400,"scope.invalid");}
    }
    public record Saved(String id,String name,List<String> tables,String recordedAt){public Saved{tables=List.copyOf(tables);}}
    public record Page(List<Saved> rows,String next){public Page{rows=List.copyOf(rows);}}
    public record Imported(List<String> tables,List<String> missing,List<String> outside,boolean wholeSchema){
        public Imported{tables=List.copyOf(tables);missing=List.copyOf(missing);outside=List.copyOf(outside);}
    }
    public static List<String> names(List<String> names){
        if(names==null||names.isEmpty()||names.size()>Ontology.GRAPH_TABLE_LIMIT)throw new Ontology.Failure(400,"scope.selection");
        var sorted=new TreeSet<String>();for(var n:names){Ontology.name(n);if(!sorted.add(n))throw new Ontology.Failure(400,"scope.invalid");}return List.copyOf(sorted);
    }
    private static String identifier(String value){
        if(value==null||value.isBlank())throw new Ontology.Failure(400,"scope.profileInvalid");
        String result=value.strip();
        if(result.startsWith("\"")){
            if(!result.matches("\"(?:[^\"]|\"\")+\""))throw new Ontology.Failure(400,"scope.profileInvalid");
            result=result.substring(1,result.length()-1).replace("\"\"","\"");
        }else{if(result.indexOf('"')>=0)throw new Ontology.Failure(400,"scope.profileInvalid");result=result.toUpperCase(Locale.ROOT);}
        Ontology.name(result);return result;
    }
    public static Imported fromProfile(String objectList,String schema,Collection<String> available,JsonMapper json){
        try{
            var root=json.readTree(objectList);if(root==null||!root.isArray()||root.isEmpty()||root.size()>5000)throw new Ontology.Failure(400,"scope.profileInvalid");
            var selected=new TreeSet<String>();var missing=new TreeSet<String>();var outside=new TreeSet<String>();boolean whole=false;
            for(var item:root){
                if(!item.isObject()||!item.path("owner").isString()||item.has("name")&&!item.path("name").isString())throw new Ontology.Failure(400,"scope.profileInvalid");
                String owner=identifier(item.path("owner").asString()),name=item.has("name")?identifier(item.path("name").asString()):null;
                if(!owner.equals(schema)){outside.add(owner+"."+(name==null?"*":name));continue;}
                if(name==null){whole=true;selected.addAll(available);}else if(available.contains(name))selected.add(name);else missing.add(name);
            }
            return new Imported(List.copyOf(selected),List.copyOf(missing),List.copyOf(outside),whole);
        }catch(Ontology.Failure ex){throw ex;}catch(RuntimeException ex){throw new Ontology.Failure(400,"scope.profileInvalid");}
    }
}
