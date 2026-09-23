package com.dbcompanion.repository;

import com.dbcompanion.model.PropertyGraph;
import com.dbcompanion.model.Ontology.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PropertyGraphRepository {
    private final JdbcTemplate jdbc;
    public PropertyGraphRepository(JdbcTemplate template){jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(20);}
    public PropertyGraph.Access access(String schema,String login,String name){
        var privileges=jdbc.queryForList("SELECT PRIVILEGE FROM SESSION_PRIVS WHERE PRIVILEGE IN ('CREATE PROPERTY GRAPH','CREATE ANY PROPERTY GRAPH')",String.class);
        var objects=jdbc.query("SELECT OBJECT_TYPE,STATUS FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=?",(r,n)->r.getString(1)+" · "+r.getString(2),schema,name);
        int major=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>)c->c.getMetaData().getDatabaseMajorVersion());
        return new PropertyGraph.Access(major>=23,schema.equals(login),!privileges.isEmpty(),!objects.isEmpty(),String.join(", ",objects));
    }
    /** Cheap, scoped dictionary checks only. No full data scans. */
    public void verify(List<Entry> entries,PropertyGraph.Definition definition){
        var included=new HashSet<String>();definition.items().stream().filter(i->i.kind().equals("TABLE")&&i.status().equals("INCLUDED")).forEach(i->included.add(i.name()));
        verifyMetadata(entries.stream().filter(e->included.contains(e.document().source().table())).toList());
    }
    public void verifyMetadata(List<Entry> entries){
        for(var entry:entries){
            var snapshot=entry.document().source();
            var actual=jdbc.query("SELECT COLUMN_NAME,DATA_TYPE,NULLABLE FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",(r,n)->r.getString(1)+":"+r.getString(2).replaceAll("\\(.*?\\)","")+":"+r.getString(3),snapshot.schema(),snapshot.table());
            var expected=snapshot.columns().stream().map(c->c.name()+":"+c.dataType().replaceAll("\\(.*?\\)","")+":"+c.nullable()).toList();
            if(!actual.equals(expected))throw new Failure(409,"pg.metadataChanged");
            var keys=jdbc.query("SELECT c.CONSTRAINT_NAME,c.CONSTRAINT_TYPE,c.STATUS,c.VALIDATED,k.COLUMN_NAME,k.POSITION FROM SYS.ALL_CONSTRAINTS c JOIN SYS.ALL_CONS_COLUMNS k ON k.OWNER=c.OWNER AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME AND k.TABLE_NAME=c.TABLE_NAME WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE IN ('P','U','R') ORDER BY c.CONSTRAINT_NAME,k.POSITION",(r,n)->r.getString(1)+":"+r.getString(2)+":"+r.getString(3)+":"+r.getString(4)+":"+r.getString(5)+":"+r.getInt(6),snapshot.schema(),snapshot.table());
            var stored=new ArrayList<String>();snapshot.keys().stream().sorted(Comparator.comparing(Key::name)).forEach(k->{for(int i=0;i<k.columns().size();i++)stored.add(k.name()+":"+k.type()+":"+k.status()+":"+k.validated()+":"+k.columns().get(i)+":"+(i+1));});
            if(!keys.equals(stored))throw new Failure(409,"pg.metadataChanged");
            // Also check FK destination identities, not just local column names/status.
            var targets=jdbc.query("SELECT c.CONSTRAINT_NAME,c.R_OWNER,r.TABLE_NAME,k.COLUMN_NAME,k.POSITION FROM SYS.ALL_CONSTRAINTS c JOIN SYS.ALL_CONSTRAINTS r ON r.OWNER=c.R_OWNER AND r.CONSTRAINT_NAME=c.R_CONSTRAINT_NAME JOIN SYS.ALL_CONS_COLUMNS k ON k.OWNER=r.OWNER AND k.CONSTRAINT_NAME=r.CONSTRAINT_NAME AND k.TABLE_NAME=r.TABLE_NAME WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE='R' ORDER BY c.CONSTRAINT_NAME,k.POSITION",(r,n)->r.getString(1)+":"+r.getString(2)+":"+r.getString(3)+":"+r.getString(4)+":"+r.getInt(5),snapshot.schema(),snapshot.table());
            var saved=new ArrayList<String>();snapshot.keys().stream().filter(k->k.type().equals("R")).sorted(Comparator.comparing(Key::name)).forEach(k->{for(int i=0;i<k.targetColumns().size();i++)saved.add(k.name()+":"+k.targetOwner()+":"+k.targetTable()+":"+k.targetColumns().get(i)+":"+(i+1));});
            if(!targets.equals(saved))throw new Failure(409,"pg.metadataChanged");
        }
    }
    public PropertyGraph.Created create(PropertyGraph.Definition value){
        jdbc.execute(value.sql());
        var rows=jdbc.queryForList("SELECT STATUS FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND OBJECT_TYPE='PROPERTY GRAPH'",String.class,value.schema(),value.name());
        return new PropertyGraph.Created(value.schema(),value.name(),rows.isEmpty()?"UNKNOWN":rows.getFirst(),"SELECT * FROM GRAPH_TABLE ("+PropertyGraph.quote(value.schema())+"."+PropertyGraph.quote(value.name())+" MATCH (a)-[e]->(b) COLUMNS (VERTEX_ID(a) AS SOURCE_ID, EDGE_ID(e) AS EDGE_ID, VERTEX_ID(b) AS TARGET_ID)) FETCH FIRST 10 ROWS ONLY");
    }
}
