package com.dbcompanion.repository;

import com.dbcompanion.common.db.OntologySql;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class OntologyRepository {
    private final JdbcTemplate jdbc;private final JsonMapper json;private final DatabaseRepository metadata;private final TableStructureRepository structure;
    public OntologyRepository(JdbcTemplate jdbc,JsonMapper json,DatabaseRepository metadata,TableStructureRepository structure){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(Ontology.READ_TIMEOUT_SECONDS);this.json=json;this.metadata=metadata;this.structure=structure;}
    public static List<String> layout(){return List.of("SEQ:NUMBER:N:0","FORMAT_VERSION:NUMBER:N:0","OBJECT_OWNER:VARCHAR2:N:128","OBJECT_NAME:VARCHAR2:N:128","REVISION:NUMBER:N:0","DOCUMENT_ID:VARCHAR2:N:36","STATE:VARCHAR2:N:12","ACTOR:VARCHAR2:N:128","RECORDED_AT:TIMESTAMP(6) WITH TIME ZONE:N:0","PAYLOAD:CLOB:N:0");}
    public String readiness(String schema,String login){
        var types=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=?",String.class,schema,OntologySql.TABLE);
        if(types.isEmpty())return schema.equals(login)?"MISSING":"UNAVAILABLE";
        if(!types.equals(List.of("TABLE")))return "MISMATCH";
        var columns=jdbc.query("SELECT COLUMN_NAME,DATA_TYPE,NULLABLE,CHAR_LENGTH FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID",(r,n)->r.getString(1)+":"+r.getString(2)+":"+r.getString(3)+":"+r.getInt(4),schema,OntologySql.TABLE);
        if(!columns.equals(layout()))return "MISMATCH";
        var keys=jdbc.query("SELECT c.CONSTRAINT_TYPE,LISTAGG(k.COLUMN_NAME,',') WITHIN GROUP (ORDER BY k.POSITION) FROM SYS.ALL_CONSTRAINTS c JOIN SYS.ALL_CONS_COLUMNS k ON k.OWNER=c.OWNER AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME AND k.TABLE_NAME=c.TABLE_NAME WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE IN ('P','U') AND c.STATUS='ENABLED' AND c.VALIDATED='VALIDATED' GROUP BY c.CONSTRAINT_TYPE,c.CONSTRAINT_NAME",(r,n)->r.getString(1)+":"+r.getString(2),schema,OntologySql.TABLE);
        if(!new HashSet<>(keys).equals(Set.of("P:SEQ","U:OBJECT_OWNER,OBJECT_NAME,REVISION")))return "MISMATCH";
        var checks=jdbc.queryForList("SELECT SEARCH_CONDITION_VC FROM SYS.ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? AND CONSTRAINT_NAME='DBC_ONTOLOGY_V1' AND CONSTRAINT_TYPE='C' AND STATUS='ENABLED' AND VALIDATED='VALIDATED'",String.class,schema,OntologySql.TABLE);
        if(checks.size()!=1||!checks.getFirst().replaceAll("[\\s\"()]","").equalsIgnoreCase("FORMAT_VERSION=1"))return "MISMATCH";
        if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TAB_IDENTITY_COLS WHERE OWNER=? AND TABLE_NAME=? AND COLUMN_NAME='SEQ' AND GENERATION_TYPE='ALWAYS'",Long.class,schema,OntologySql.TABLE)!=1L)return "MISMATCH";
        if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME=? AND STATUS='ENABLED'",Long.class,schema,OntologySql.TABLE)!=0L)return "MISMATCH";
        return "READY";
    }
    public void require(String schema,String login){if(!readiness(schema,login).equals("READY"))throw new Failure(409,"notReady");}
    public void install(String schema,String login){
        if(!schema.equals(login))throw new Failure(403,"ownerRequired");String state=readiness(schema,login);
        if(state.equals("READY"))return;if(!state.equals("MISSING"))throw new Failure(409,"mismatch");jdbc.execute(OntologySql.create(schema));require(schema,login);
    }
    public Catalog catalog(String schema,String login){
        return catalog(schema,login,()->tables(schema));
    }
    public Catalog catalog(String schema,String login,Supplier<List<TableInfo>> tableMetadata){
        String status=readiness(schema,login);if(!status.equals("READY"))return new Catalog(status,schema.equals(login)&&status.equals("MISSING"),List.of(),List.of(),Instant.now().toString());
        var tables=tableMetadata.get();
        var entries=jdbc.query("SELECT OBJECT_NAME,REVISION,STATE,ACTOR,RECORDED_AT FROM (SELECT OBJECT_NAME,REVISION,STATE,ACTOR,RECORDED_AT,ROW_NUMBER() OVER (PARTITION BY OBJECT_OWNER,OBJECT_NAME ORDER BY REVISION DESC) RN FROM "+OntologySql.table(schema)+" WHERE OBJECT_OWNER=?) WHERE RN=1 ORDER BY OBJECT_NAME FETCH FIRST 5001 ROWS ONLY",(r,n)->new Summary(r.getString(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5)),schema);
        if(tables.size()>5000||entries.size()>5000)throw new Failure(413,"limit");return new Catalog(status,false,tables,entries,Instant.now().toString());
    }
    public List<TableInfo> tables(String schema){
        var names=jdbc.queryForList("SELECT TABLE_NAME FROM SYS.ALL_TABLES WHERE OWNER=? AND TABLE_NAME<>? ORDER BY TABLE_NAME FETCH FIRST 5001 ROWS ONLY",String.class,schema,OntologySql.TABLE);
        if(names.size()>5000)throw new Failure(413,"limit");
        // Joining these two ALL_* views exceeded even 30 seconds on the affected database.
        // Bounded, name-bound batches avoid that dictionary JOIN without losing null comments.
        var comments=new HashMap<String,String>();
        for(int start=0;start<names.size();start+=500){
            var batch=names.subList(start,Math.min(start+500,names.size()));
            var args=new ArrayList<Object>();args.add(schema);args.addAll(batch);
            jdbc.query("SELECT TABLE_NAME,COMMENTS FROM SYS.ALL_TAB_COMMENTS WHERE OWNER=? AND TABLE_TYPE='TABLE' AND TABLE_NAME IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",r->{comments.put(r.getString(1),r.getString(2));},args.toArray());
        }
        return names.stream().map(name->new TableInfo(name,comments.get(name))).toList();
    }
    public Snapshot snapshot(String database,String schema,String table){
        Ontology.name(table);if(table.equals(OntologySql.TABLE))throw new Failure(400,"invalid");var info=metadata.table(schema,table);if(info==null)throw new Failure(404,"notFound");
        var columns=metadata.columns(schema,table);var keys=structure.constraints(schema,table).stream().filter(c->Set.of("P","U","R").contains(c.type())).map(c->new Key(c.name(),c.type(),c.columns(),c.referenceOwner(),c.referenceTable(),c.referenceColumns(),c.status(),c.validated())).toList();
        if(columns.size()>1000||keys.size()>1000)throw new Failure(413,"limit");
        String comment=info.description();
        if(comment==null){var mv=jdbc.queryForList("SELECT COMMENTS FROM SYS.ALL_MVIEW_COMMENTS WHERE OWNER=? AND MVIEW_NAME=?",String.class,schema,table);if(!mv.isEmpty())comment=mv.getFirst();}
        return new Snapshot(database,schema,table,comment,columns,keys,Instant.now().toString());
    }
    public GraphData graph(String database,String schema,String login){
        require(schema,login);
        // Project keys only; columns/comments remain lazy in the existing detail endpoint.
        String sql="""
                SELECT OBJECT_NAME,REVISION,DOCUMENT_ID,STATE,RECORDED_AT,
                  JSON_VALUE(PAYLOAD,'$.source.database' RETURNING VARCHAR2(128) ERROR ON ERROR),
                  JSON_VALUE(PAYLOAD,'$.source.schema' RETURNING VARCHAR2(128) ERROR ON ERROR),
                  JSON_VALUE(PAYLOAD,'$.source.table' RETURNING VARCHAR2(128) ERROR ON ERROR),
                  JSON_VALUE(PAYLOAD,'$.source.capturedAt' RETURNING VARCHAR2(128) ERROR ON ERROR),
                  JSON_QUERY(PAYLOAD,'$.source.keys' RETURNING CLOB ERROR ON ERROR)
                FROM (SELECT OBJECT_NAME,REVISION,DOCUMENT_ID,STATE,RECORDED_AT,PAYLOAD,
                  ROW_NUMBER() OVER (PARTITION BY OBJECT_OWNER,OBJECT_NAME ORDER BY REVISION DESC) RN
                  FROM %s WHERE OBJECT_OWNER=?) WHERE RN=1 ORDER BY OBJECT_NAME FETCH FIRST 501 ROWS ONLY
                """.formatted(OntologySql.table(schema));
        var tables=jdbc.query(sql,(row,index)->{
            if(!database.equals(row.getString(6))||!schema.equals(row.getString(7))||!row.getString(1).equals(row.getString(8)))throw new Failure(409,"mismatch");
            var clob=row.getClob(10);if(clob==null)throw new Failure(409,"mismatch");
            try{
                if(clob.length()>Ontology.MAX_JSON)throw new Failure(413,"erd.limit");
                Key[] keys=json.readValue(clob.getSubString(1,(int)clob.length()),Key[].class);
                if(keys==null||keys.length>1000)throw new Failure(409,"mismatch");
                for(var key:keys){
                    if(key==null||!Set.of("P","U","R").contains(key.type()))throw new Failure(409,"mismatch");
                    Ontology.name(key.name());key.columns().forEach(Ontology::name);key.targetColumns().forEach(Ontology::name);
                    if(key.targetOwner()!=null)Ontology.name(key.targetOwner());if(key.targetTable()!=null)Ontology.name(key.targetTable());
                }
                return new GraphTable(row.getString(1),row.getInt(2),row.getString(3),row.getString(4),row.getString(5),row.getString(9),Arrays.asList(keys));
            }finally{clob.free();}
        },schema);
        return new GraphData(schema,tables,Instant.now().toString());
    }
    public Entry entry(String schema,String table,int revision){
        var rows=jdbc.query("SELECT SEQ,REVISION,DOCUMENT_ID,STATE,ACTOR,RECORDED_AT,PAYLOAD FROM "+OntologySql.table(schema)+" WHERE OBJECT_OWNER=? AND OBJECT_NAME=?"+(revision>0?" AND REVISION=?":"")+" ORDER BY REVISION DESC FETCH FIRST 1 ROW ONLY",(r,n)->new Entry(r.getString(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),payload(r)),revision>0?new Object[]{schema,table,revision}:new Object[]{schema,table});
        if(rows.isEmpty())return null;var result=rows.getFirst();
        if(!schema.equals(result.document().source().schema())||!table.equals(result.document().source().table()))throw new Failure(409,"mismatch");return result;
    }
    public List<Entry> relationshipEntries(String schema,String login){
        return relationshipEntriesQuery(schema,login,null);
    }
    /** Filter in SQL before reading CLOBs. A missing selection is never interpreted as the whole catalog. */
    public List<Entry> relationshipEntries(String schema,String login,List<String> tables){
        var names=OntologyScope.names(tables);var rows=relationshipEntriesQuery(schema,login,names);
        if(!rows.stream().map(e->e.document().source().table()).toList().equals(names))throw new Failure(409,"scope.missingDefinition");return rows;
    }
    private List<Entry> relationshipEntriesQuery(String schema,String login,List<String> tables){
        require(schema,login);long[] total={0};
        var args=new ArrayList<Object>();args.add(schema);if(tables!=null)args.addAll(tables);
        String selection=tables==null?"":" AND OBJECT_NAME IN ("+String.join(",",Collections.nCopies(tables.size(),"?"))+")";
        return jdbc.query("SELECT SEQ,REVISION,DOCUMENT_ID,STATE,ACTOR,RECORDED_AT,PAYLOAD,OBJECT_NAME FROM (SELECT SEQ,REVISION,DOCUMENT_ID,STATE,ACTOR,RECORDED_AT,PAYLOAD,OBJECT_NAME,ROW_NUMBER() OVER (PARTITION BY OBJECT_OWNER,OBJECT_NAME ORDER BY REVISION DESC) RN FROM "+OntologySql.table(schema)+" WHERE OBJECT_OWNER=?"+selection+") WHERE RN=1 ORDER BY OBJECT_NAME FETCH FIRST 501 ROWS ONLY",(r,n)->{
            if(n>=Ontology.GRAPH_TABLE_LIMIT)throw new Failure(413,"relationships.limit");
            var clob=r.getClob(7);if(clob==null)throw new Failure(409,"mismatch");
            try{long length=clob.length();total[0]+=length;if(length>Ontology.MAX_JSON||total[0]>OntologyRelations.MAX_JSON)throw new Failure(413,"relationships.limit");
                var document=Ontology.parse(clob.getSubString(1,(int)length),json);
                if(!schema.equals(document.source().schema())||!r.getString(8).equals(document.source().table()))throw new Failure(409,"mismatch");
                return new Entry(r.getString(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),document);
            }finally{clob.free();}
        },args.toArray());
    }
    private Document payload(java.sql.ResultSet row) throws java.sql.SQLException {
        var clob=row.getClob(7);if(clob==null)throw new Failure(409,"mismatch");
        try{long size=clob.length();if(size>Ontology.MAX_JSON)throw new Failure(413,"limit");return Ontology.parse(clob.getSubString(1,(int)size),json);}finally{clob.free();}
    }
    public Entry append(String schema,String table,int expected,String state,Document document){
        if(!Set.of("DRAFT","APPROVED").contains(state)||expected<0||expected>=Integer.MAX_VALUE)throw new Failure(400,"invalid");
        var before=entry(schema,table,0);if((before==null?0:before.revision())!=expected)throw new Failure(409,"stale");
        String id=before==null?UUID.randomUUID().toString():before.documentId(),payload=Ontology.json(document,json);int revision=expected+1;
        // Unique(owner,name,revision) arbitrates concurrent writers in different app sessions.
        jdbc.update("INSERT INTO "+OntologySql.table(schema)+" (FORMAT_VERSION,OBJECT_OWNER,OBJECT_NAME,REVISION,DOCUMENT_ID,STATE,ACTOR,RECORDED_AT,PAYLOAD) VALUES (1,?,?,?,?,?,SYS_CONTEXT('USERENV','SESSION_USER'),SYSTIMESTAMP,?)",s->{s.setString(1,schema);s.setString(2,table);s.setInt(3,revision);s.setString(4,id);s.setString(5,state);s.setCharacterStream(6,new StringReader(payload),payload.length());});
        var after=entry(schema,table,revision);if(after==null||!after.document().equals(document))throw new Failure(409,"verifyFailed");return after;
    }
    public History history(String schema,String table,String before){
        Ontology.cursor(before);var args=new ArrayList<Object>(List.of(schema,table));if(!before.isEmpty())args.add(new BigDecimal(before));
        var rows=jdbc.query("SELECT SEQ,REVISION,STATE,ACTOR,RECORDED_AT FROM "+OntologySql.table(schema)+" WHERE OBJECT_OWNER=? AND OBJECT_NAME=?"+(before.isEmpty()?"":" AND SEQ<?")+" ORDER BY SEQ DESC FETCH FIRST 11 ROWS ONLY",(r,n)->new Version(r.getString(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5)),args.toArray());
        var page=rows.stream().limit(10).toList();return new History(page,rows.size()>10?page.getLast().seq():"");
    }
}
