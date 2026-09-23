package com.dbcompanion.repository;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.service.QueryArchiveRdf;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.*;
import org.eclipse.rdf4j.model.Model;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NativeRdfRepository {
    private final JdbcTemplate jdbc;
    public NativeRdfRepository(JdbcTemplate jdbc){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(30);}
    public boolean api(){return jdbc.queryForObject("SELECT COUNT(DISTINCT PROCEDURE_NAME) FROM SYS.ALL_PROCEDURES WHERE OWNER='MDSYS' AND OBJECT_NAME='SEM_APIS' AND PROCEDURE_NAME IN ('CREATE_RDF_NETWORK','CREATE_RDF_GRAPH')",Integer.class)==2;}
    public String tablespace(){return jdbc.queryForObject("SELECT DEFAULT_TABLESPACE FROM SYS.USER_USERS",String.class);}
    public boolean exists(String schema){return jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME LIKE 'DBC!_RDF#%' ESCAPE '!'",Integer.class,schema)>0;}
    public String modelId(String schema){
        var views=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND STATUS='VALID'",String.class,schema,RdfQuerySql.VIEW);
        if(!views.equals(List.of("VIEW")))throw new Ontology.Failure(409,"archive.rdfMismatch");
        var columns=jdbc.query("SELECT COLUMN_NAME,DATA_TYPE_OWNER,DATA_TYPE FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",(r,n)->r.getString(1)+":"+r.getString(2)+":"+r.getString(3),schema,RdfQuerySql.VIEW);
        if(!columns.equals(List.of("TRIPLE:MDSYS:SDO_RDF_TRIPLE_S")))throw new Ontology.Failure(409,"archive.rdfMismatch");
        var values=jdbc.queryForList("SELECT TO_CHAR(MODEL_ID) FROM "+ProfileHistorySql.object(schema,RdfQuerySql.NETWORK+"#SEM_MODEL$")+" WHERE MODEL_NAME=? AND OWNER=? AND MODEL_TYPE='M' AND TABLE_NAME IS NULL AND COLUMN_NAME IS NULL",String.class,RdfQuerySql.MODEL,schema);
        if(values.size()!=1)throw new Ontology.Failure(409,"archive.rdfMismatch");return values.getFirst();
    }
    public void create(String schema,String tablespace){
        if(exists(schema))throw new Ontology.Failure(409,"archive.rdfMismatch");
        try{jdbc.update(RdfQuerySql.CREATE_NETWORK,tablespace,schema);}catch(RuntimeException ex){throw new StageFailure("MDSYS.SEM_APIS.CREATE_RDF_NETWORK",ex);}
        try{jdbc.update(RdfQuerySql.CREATE_MODEL,schema);}catch(RuntimeException ex){throw new StageFailure("MDSYS.SEM_APIS.CREATE_RDF_GRAPH",ex);}modelId(schema);
    }
    public void probe(String schema){
        // A fixed, nonexistent named graph is queried. Never read arbitrary application rows.
        var rows=jdbc.query(RdfQuerySql.select(schema,"00000000-0000-0000-0000-000000000000",false),(r,n)->1);
        if(!rows.isEmpty())throw new Ontology.Failure(409,"archive.rdfMismatch");
    }
    public void insert(String schema,String id,boolean result,Model model){
        var rows=QueryArchiveRdf.rows(model);String graph=RdfQuerySql.MODEL+":<"+QueryArchive.graph(id,result)+">";
        jdbc.batchUpdate(RdfQuerySql.insert(schema),rows,100,(s,row)->{
            s.setString(1,graph);s.setString(2,row.subject());s.setString(3,row.predicate());s.setString(4,row.object());s.setString(5,schema);s.setString(6,RdfQuerySql.NETWORK);
        });
    }
    private String term(Clob value)throws SQLException{
        if(value==null)throw new Ontology.Failure(409,"archive.invalidRdf");try{if(value.length()>4000)throw new Ontology.Failure(413,"archive.termLimit");return value.getSubString(1,(int)value.length());}finally{value.free();}
    }
    public Model construct(String schema,String id,boolean result){
        long[] size={0};
        var rows=jdbc.query(RdfQuerySql.select(schema,id,result),(r,n)->{
            if(n>=QueryArchive.MAX_TRIPLES)throw new Ontology.Failure(413,"archive.limit");var row=new QueryArchiveRdf.Row(term(r.getClob(1)),term(r.getClob(2)),term(r.getClob(3)));
            size[0]+=row.subject().length()+row.predicate().length()+row.object().length()+4;if(size[0]>QueryArchive.MAX_JSON)throw new Ontology.Failure(413,"archive.limit");return row;
        });return QueryArchiveRdf.fromRows(rows);
    }
    public static final class StageFailure extends RuntimeException {
        private final String operation;
        public StageFailure(String operation,RuntimeException cause){super(operation,cause);this.operation=operation;}
        public String operation(){return operation;}
    }
}
