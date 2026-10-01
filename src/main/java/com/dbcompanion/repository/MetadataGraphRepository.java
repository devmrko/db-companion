package com.dbcompanion.repository;

import com.dbcompanion.model.MetadataGraph.*;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.common.i18n.UiMessages;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetadataGraphRepository {
    private final JdbcTemplate jdbc;
    public MetadataGraphRepository(JdbcTemplate jdbc){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(45);}
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
