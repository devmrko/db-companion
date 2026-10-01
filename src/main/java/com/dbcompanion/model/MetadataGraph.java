package com.dbcompanion.model;

import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyRelations.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Catalog metadata graph. Never treats source business rows as vertices. */
public final class MetadataGraph {
    private MetadataGraph() { }
    public record Plan(String schema,String name,String nodeView,String edgeView,String nodesSql,String edgesSql,
                       List<String> ddl,String query,int objects,int columns,int relations,int mappings,List<PropertyGraph.Item> excluded) {
        public Plan {ddl=List.copyOf(ddl);excluded=List.copyOf(excluded);}
    }
    public record Access(boolean supported,boolean owner,boolean graphPrivilege,boolean viewPrivilege,List<String> collisions) {
        public Access {collisions=List.copyOf(collisions);}
        public boolean allowed(){return supported&&owner&&graphPrivilege&&viewPrivilege&&collisions.isEmpty();}
    }
    public record Preview(String token,Plan plan,Access access,Instant expires,boolean canCreate) { }
    public record Draft(Preview preview,List<Entry> entries) {public Draft {entries=List.copyOf(entries);}}
    public record Created(String name,List<String> objects,String query) {public Created {objects=List.copyOf(objects);}}
    public static final class State {
        private Draft draft;
        public synchronized void clear(){draft=null;}
        public synchronized void prepare(Draft value){draft=value;}
        public synchronized Draft consume(String schema,String token,boolean confirmed,Instant now){
            if(!confirmed)throw new Failure(400,"confirmRequired");
            if(draft==null||!draft.preview().token().equals(token)||!draft.preview().plan().schema().equals(schema)||!now.isBefore(draft.preview().expires()))throw new Failure(409,"stale");
            var value=draft;draft=null;return value;
        }
    }
    private static String resource(String name){
        try(var in=MetadataGraph.class.getResourceAsStream("/sql/metadata-graph/"+name+".sql")){
            if(in==null)throw new IllegalStateException("Missing metadata graph SQL");return new String(in.readAllBytes(),StandardCharsets.UTF_8).strip();
        }catch(IOException ex){throw new IllegalStateException(ex);}
    }
    private static String literal(String value){return "'"+value.replace("'","''")+"'";}
    private static String object(String schema,String name){return PropertyGraph.quote(schema)+"."+PropertyGraph.quote(name);}
    private static String seq(Entry entry){if(entry.seq()==null||!entry.seq().matches("[1-9][0-9]{0,37}"))throw new Failure(409,"mismatch");return entry.seq();}
    private static boolean mapping(Relation r,Entry a,Entry b){
        if(a==null||b==null||r.sourceColumns().isEmpty()||r.sourceColumns().size()!=r.targetColumns().size())return false;
        return new HashSet<>(r.sourceColumns()).size()==r.sourceColumns().size()&&new HashSet<>(r.targetColumns()).size()==r.targetColumns().size()
            &&a.document().source().columns().stream().map(ColumnInfo::name).toList().containsAll(r.sourceColumns())
            &&b.document().source().columns().stream().map(ColumnInfo::name).toList().containsAll(r.targetColumns());
    }
    public static Plan build(String schema,String requested,List<Entry> entries,Analysis analysis){
        Ontology.name(schema);String name=PropertyGraph.graphName(requested),nodeView=name+"_NODES",edgeView=name+"_EDGES";
        if(entries.isEmpty()||entries.size()>Ontology.GRAPH_TABLE_LIMIT)throw new Failure(400,"mg.empty");
        if(!analysis.schema().equals(schema))throw new Failure(409,"mismatch");
        var byName=new LinkedHashMap<String,Entry>();var seqs=new HashSet<String>();int columns=0;
        for(var e:entries){if(!schema.equals(e.document().source().schema())||byName.putIfAbsent(e.document().source().table(),e)!=null||!seqs.add(seq(e)))throw new Failure(409,"mismatch");columns+=e.document().source().columns().size();}
        if(columns>OntologyRelations.MAX_COLUMNS)throw new Failure(413,"limit");
        var links=new ArrayList<String>();var fks=new ArrayList<String>();var excluded=new ArrayList<PropertyGraph.Item>();int relations=0,mappings=0;
        for(var r:analysis.relations()){
            var a=byName.get(r.source());var b=byName.get(r.target());String reason="";
            if(!Set.of("APPROVED","FK").contains(r.status()))reason="UNCONFIRMED";
            else if(!schema.equals(r.targetSchema())||!mapping(r,a,b))reason="MAPPING";
            else if("FK".equals(r.status())&&(r.key()==null||!"ENABLED".equals(r.key().status())||!"VALIDATED".equals(r.key().validated())))reason="UNCONFIRMED";
            else if("APPROVED".equals(r.status())&&(r.review()==null||!a.document().links().contains(r.review())))reason="UNCONFIRMED";
            if(!reason.isEmpty()){excluded.add(new PropertyGraph.Item("RELATION",r.source()+" → "+r.target()+" · "+r.label(),"","EXCLUDED",reason));continue;}
            if("FK".equals(r.status()))fks.add("(c.SEQ="+seq(a)+" AND k.name="+literal(r.key().name())+")");
            else links.add("(c.SEQ="+seq(a)+" AND l.id="+literal(r.id())+")");
            relations++;mappings+=r.sourceColumns().size();
        }
        String selected=entries.stream().map(MetadataGraph::seq).collect(Collectors.joining(","));
        String nodes=resource("nodes").replace("@@CATALOG@@",object(schema,"DBC_ONTOLOGY_CATALOG")).replace("@@OWNER@@",literal(schema)).replace("@@SEQS@@",selected);
        String edges=resource("edges").replace("@@CATALOG@@",object(schema,"DBC_ONTOLOGY_CATALOG")).replace("@@OWNER@@",literal(schema)).replace("@@SEQS@@",selected)
            .replace("@@LINK_FILTER@@",links.isEmpty()?"1=0":String.join(" OR ",links)).replace("@@FK_FILTER@@",fks.isEmpty()?"1=0":String.join(" OR ",fks));
        String graph="CREATE PROPERTY GRAPH "+object(schema,name)+"\nVERTEX TABLES ("+object(schema,nodeView)+" AS META_NODE KEY (NODE_ID)\n LABEL METADATA_NODE PROPERTIES (NODE_KIND,OWNER_NAME,OBJECT_NAME,COLUMN_NAME,DATA_TYPE,REVISION))\nEDGE TABLES ("+object(schema,edgeView)+" AS META_EDGE KEY (EDGE_ID)\n SOURCE KEY (SOURCE_ID) REFERENCES META_NODE (NODE_ID)\n DESTINATION KEY (TARGET_ID) REFERENCES META_NODE (NODE_ID)\n LABEL METADATA_LINK PROPERTIES (EDGE_KIND,RELATION_ID,RELATION_LABEL,CONDITION_TEXT,ORIGIN,RELATION_STATE,MAPPING_POSITION,MAPPING_COUNT))\nOPTIONS (TRUSTED MODE)";
        var ddl=List.of("CREATE VIEW "+object(schema,nodeView)+" AS\n"+nodes,"CREATE VIEW "+object(schema,edgeView)+" AS\n"+edges,graph);
        if(ddl.stream().mapToInt(String::length).sum()>500_000)throw new Failure(413,"limit");
        String query="SELECT * FROM GRAPH_TABLE ("+object(schema,name)+"\n MATCH (a IS METADATA_NODE)-[e IS METADATA_LINK]->(b IS METADATA_NODE)\n COLUMNS (a.OBJECT_NAME AS SOURCE_OBJECT, a.COLUMN_NAME AS SOURCE_COLUMN,\n e.EDGE_KIND AS EDGE_KIND,e.RELATION_ID AS RELATION_ID,e.RELATION_LABEL AS RELATION_LABEL,\n e.MAPPING_POSITION AS MAPPING_POSITION,e.MAPPING_COUNT AS MAPPING_COUNT,e.CONDITION_TEXT AS CONDITION_TEXT,\n b.OBJECT_NAME AS TARGET_OBJECT,b.COLUMN_NAME AS TARGET_COLUMN))\nFETCH FIRST 100 ROWS ONLY";
        return new Plan(schema,name,nodeView,edgeView,nodes,edges,ddl,query,entries.size(),columns,relations,mappings,excluded);
    }
}
