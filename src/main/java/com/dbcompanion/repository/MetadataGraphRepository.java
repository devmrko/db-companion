package com.dbcompanion.repository;

import com.dbcompanion.model.MetadataGraph.*;
import com.dbcompanion.model.MetadataGraph;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.model.PropertyGraph;
import com.dbcompanion.common.i18n.UiMessages;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class MetadataGraphRepository {
    private static final JsonMapper JSON=new JsonMapper();
    private final JdbcTemplate jdbc;
    public MetadataGraphRepository(JdbcTemplate jdbc){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(45);}
    public record Existing(String name,String status,String nodesStatus,String edgesStatus,boolean metadataCandidate) { }
    public record GraphSearch(String name,String sql,List<String> parameters,List<Map<String,Object>> rows){public GraphSearch{parameters=List.copyOf(parameters);rows=List.copyOf(rows);}}
    public GraphSearch searchObjects(String schema,String requested,List<String> objects){
        String name=PropertyGraph.graphName(requested);
        if(existing(schema).stream().noneMatch(g->g.name().equals(name)&&g.metadataCandidate()))throw new Ontology.Failure(409,"mg.notQueryable");
        if(objects.size()>30)throw new Ontology.Failure(413,"limit");objects.forEach(Ontology::name);
        String source=MetadataGraph.readQuery(schema,name).replace("\nFETCH FIRST 100 ROWS ONLY","");
        String marks=String.join(",",Collections.nCopies(objects.size(),"?"));
        String where=objects.isEmpty()?"1=0":"(SOURCE_OBJECT IN ("+marks+") OR TARGET_OBJECT IN ("+marks+"))";
        String sql="SELECT * FROM ("+source+") WHERE EDGE_KIND IN ('RELATES_TO','MAPS_TO') AND "+where+" ORDER BY SOURCE_OBJECT,TARGET_OBJECT,RELATION_ID,MAPPING_POSITION FETCH FIRST 100 ROWS ONLY";
        var args=new ArrayList<String>(objects);args.addAll(objects);
        return new GraphSearch(name,sql,args,jdbc.query(sql,MetadataGraphRepository::readRows,args.toArray()));
    }
    public record ReadResult(String mode,List<Map<String,Object>> rows,List<Map<String,Object>> mappings,List<Map<String,Object>> columns,Map<String,Long> counts) {
        public ReadResult {rows=List.copyOf(rows);mappings=List.copyOf(mappings);columns=List.copyOf(columns);counts=Map.copyOf(counts);}
        public ReadResult(String mode,List<Map<String,Object>> rows){this(mode,rows,List.of(),List.of(),Map.of());}
    }
    /** The companion views identify a possible app metadata graph, not its provenance. */
    public List<Existing> existing(String schema){
        Ontology.name(schema);
        return jdbc.query("SELECT g.OBJECT_NAME,g.STATUS,n.STATUS,e.STATUS FROM SYS.ALL_OBJECTS g "
            +"LEFT JOIN SYS.ALL_OBJECTS n ON n.OWNER=g.OWNER AND n.OBJECT_NAME=g.OBJECT_NAME||'_NODES' AND n.OBJECT_TYPE='VIEW' "
            +"LEFT JOIN SYS.ALL_OBJECTS e ON e.OWNER=g.OWNER AND e.OBJECT_NAME=g.OBJECT_NAME||'_EDGES' AND e.OBJECT_TYPE='VIEW' "
            +"WHERE g.OWNER=? AND g.OBJECT_TYPE='PROPERTY GRAPH' ORDER BY g.OBJECT_NAME FETCH FIRST 101 ROWS ONLY",
            (r,i)->new Existing(r.getString(1),r.getString(2),r.getString(3),r.getString(4),"VALID".equals(r.getString(2))&&"VALID".equals(r.getString(3))&&"VALID".equals(r.getString(4))),schema);
    }
    /** Read-only, bounded query over a login-owned VALID graph. Generic results expose graph IDs only. */
    public ReadResult query(String schema,String requested){
        String name=PropertyGraph.graphName(requested);
        var graph=existing(schema).stream().filter(g->g.name().equals(name)&&"VALID".equals(g.status())).findFirst().orElseThrow(()->new Ontology.Failure(409,"mg.notQueryable"));
        if(graph.metadataCandidate()){
            String source=MetadataGraph.readQuery(schema,name).replace("\nFETCH FIRST 100 ROWS ONLY","");
            Map<String,Long> counts=new LinkedHashMap<>();
            jdbc.query("SELECT EDGE_KIND,COUNT(*) AS N FROM ("+source+") GROUP BY EDGE_KIND",rs->{while(rs.next())counts.put(rs.getString(1),rs.getLong(2));return counts;});
            return new ReadResult("METADATA",readKind(source,"RELATES_TO"),readKind(source,"MAPS_TO"),readKind(source,"CONTAINS"),counts);
        }
        String sql="SELECT * FROM GRAPH_TABLE ("+PropertyGraph.quote(schema)+"."+PropertyGraph.quote(name)
            +" MATCH (a)-[e]->(b) COLUMNS (VERTEX_ID(a) AS SOURCE_ID, EDGE_ID(e) AS EDGE_ID, VERTEX_ID(b) AS TARGET_ID)) FETCH FIRST 100 ROWS ONLY";
        return new ReadResult("GENERIC",jdbc.query(sql,MetadataGraphRepository::readRows));
    }
    /** Filter before limiting so containment cannot hide relationships. */
    private List<Map<String,Object>> readKind(String source,String kind){
        return jdbc.query("SELECT * FROM ("+source+") WHERE EDGE_KIND=? ORDER BY SOURCE_OBJECT,TARGET_OBJECT,RELATION_ID,MAPPING_POSITION,TARGET_COLUMN FETCH FIRST 100 ROWS ONLY",MetadataGraphRepository::readRows,kind);
    }
    /** Oracle JSON values require an explicit JDBC target type; parse the JSON text once for the HTTP response. */
    static List<Map<String,Object>> readRows(ResultSet rs) throws SQLException {
        ResultSetMetaData columns=rs.getMetaData();
        int count=columns.getColumnCount();
        var rows=new ArrayList<Map<String,Object>>();
        while(rs.next()){
            var row=new LinkedHashMap<String,Object>();
            for(int column=1;column<=count;column++){
                Object value;
                if("JSON".equalsIgnoreCase(columns.getColumnTypeName(column))){
                    String text=rs.getObject(column,String.class);
                    value=text==null?null:JSON.readTree(text);
                }else value=rs.getObject(column);
                row.put(columns.getColumnLabel(column),value);
            }
            rows.add(row);
        }
        return rows;
    }
    public Access access(Plan plan,String login){
        var privileges=jdbc.queryForList("SELECT PRIVILEGE FROM SESSION_PRIVS WHERE PRIVILEGE IN ('CREATE PROPERTY GRAPH','CREATE ANY PROPERTY GRAPH','CREATE VIEW','CREATE ANY VIEW')",String.class);
        var collisions=jdbc.query("SELECT OBJECT_NAME,OBJECT_TYPE,STATUS FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME IN (?,?,?) ORDER BY OBJECT_NAME",(r,n)->r.getString(1)+" · "+r.getString(2)+" · "+r.getString(3),plan.schema(),plan.name(),plan.nodeView(),plan.edgeView());
        int major=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>)c->c.getMetaData().getDatabaseMajorVersion());
        return new Access(major>=23,plan.schema().equals(login),privileges.stream().anyMatch(p->p.endsWith("PROPERTY GRAPH")),privileges.stream().anyMatch(p->p.endsWith("VIEW")),collisions);
    }
    /** Executes only generated SELECTs over pinned catalog revisions, not source objects. */
    public void verifyProjection(Plan plan){
        var nodes=jdbc.queryForMap("SELECT COUNT(*) N,COUNT(DISTINCT NODE_ID) U,COUNT(CASE WHEN NODE_KIND='OBJECT' THEN 1 END) O FROM ("+plan.nodesSql()+")");
        int expected=plan.objects()+plan.columns();
        if(number(nodes,"N")!=expected||number(nodes,"U")!=expected||number(nodes,"O")!=plan.objects())throw new Ontology.Failure(409,"mg.projection");
        var edges=jdbc.queryForMap("SELECT COUNT(*) N,COUNT(DISTINCT EDGE_ID) U,COUNT(CASE WHEN EDGE_KIND='RELATES_TO' THEN 1 END) R,COUNT(CASE WHEN EDGE_KIND='MAPS_TO' THEN 1 END) M FROM ("+plan.edgesSql()+")");
        int total=plan.columns()+plan.relations()+plan.mappings();
        if(number(edges,"N")!=total||number(edges,"U")!=total||number(edges,"R")!=plan.relations()||number(edges,"M")!=plan.mappings())throw new Ontology.Failure(409,"mg.projection");
    }
    private static int number(Map<String,Object> row,String key){return ((Number)row.get(key)).intValue();}
    public Created create(Plan plan){
        var completed=new ArrayList<String>();var names=List.of(plan.nodeView(),plan.edgeView(),plan.name());
        try{
            for(int i=0;i<plan.ddl().size();i++){
                jdbc.execute(plan.ddl().get(i));completed.add(names.get(i));
                var statuses=jdbc.queryForList("SELECT STATUS FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND OBJECT_TYPE=?",String.class,plan.schema(),names.get(i),i==2?"PROPERTY GRAPH":"VIEW");
                if(!statuses.equals(List.of("VALID")))throw new Ontology.Failure(409,"mg.invalidObject");
            }
            return new Created(plan.name(),completed,plan.query());
        }catch(RuntimeException ex){
            // DDL commits. Do not drop, replace or retry partially created objects.
            throw new CreationFailure(completed,ex);
        }
    }
    public static final class CreationFailure extends RuntimeException {
        private final List<String> completed;
        public CreationFailure(List<String> completed,RuntimeException cause){super(UiMessages.text("ontology.mg.partial","Creation incomplete; inspect existing objects before retrying"),cause);this.completed=List.copyOf(completed);}
        public List<String> completed(){return completed;}
    }
}
