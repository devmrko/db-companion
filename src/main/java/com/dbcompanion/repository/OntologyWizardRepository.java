package com.dbcompanion.repository;

import com.dbcompanion.common.db.MetadataSql;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OntologyWizardRepository {
    private final JdbcTemplate jdbc;
    public OntologyWizardRepository(JdbcTemplate jdbc){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);}
    public void verify(Snapshot snapshot,List<ColumnInfo> selected){
        String schema=snapshot.schema(),table=snapshot.table();
        try{OntologyQueryRepository.verifySampleSource(jdbc,schema,table);}catch(Failure ex){throw new Failure(422,"wizard.localOnly");}
        var live=jdbc.query("SELECT COLUMN_NAME,DATA_TYPE FROM SYS.ALL_TAB_COLS WHERE OWNER=? AND TABLE_NAME=? AND VIRTUAL_COLUMN='NO' AND HIDDEN_COLUMN='NO'",(r,n)->Map.entry(r.getString(1),r.getString(2)),schema,table);
        for(var c:selected){
            String type=live.stream().filter(v->v.getKey().equals(c.name())).map(Map.Entry::getValue).findFirst().orElse("");
            if(!OntologyWizard.supported(type)||!baseType(type).equals(baseType(c.dataType())))throw new Failure(409,"wizard.structureChanged");
        }
    }
    private static String baseType(String value){return value.replaceAll("\\(.*?\\)","").trim().toUpperCase(Locale.ROOT);}
    public static String sampleSql(String schema,String table,List<ColumnInfo> columns){
        if(columns.isEmpty()||columns.size()>OntologyWizard.MAX_COLUMNS)throw new Failure(400,"invalid");
        var expressions=new ArrayList<String>();
        for(var c:columns){
            if(!OntologyWizard.supported(c.dataType()))throw new Failure(400,"invalid");
            String name=MetadataSql.identifier(c.name()),type=baseType(c.dataType());
            String expr=switch(type){
                case "DATE" -> "TO_CHAR("+name+",'YYYY-MM-DD\"T\"HH24:MI:SS','NLS_DATE_LANGUAGE=American')";
                case "TIMESTAMP" -> "TO_CHAR("+name+",'YYYY-MM-DD\"T\"HH24:MI:SS.FF','NLS_DATE_LANGUAGE=American')";
                case "NUMBER","FLOAT","BINARY_FLOAT","BINARY_DOUBLE" -> "TO_CHAR("+name+",'TM9','NLS_NUMERIC_CHARACTERS=''.,''')";
                default -> "SUBSTR("+name+",1,201)";
            };
            expressions.add(expr+" AS C"+expressions.size());
        }
        return "SELECT "+String.join(",",expressions)+" FROM "+MetadataSql.identifier(schema)+"."+MetadataSql.identifier(table)+" WHERE ROWNUM <= ?";
    }
    public List<List<String>> sample(Snapshot snapshot,List<ColumnInfo> columns,int count){
        if(count!=10&&count!=20)throw new Failure(400,"invalid");verify(snapshot,columns);
        return jdbc.query(sampleSql(snapshot.schema(),snapshot.table(),columns),s->{s.setInt(1,count);s.setMaxRows(count);s.setFetchSize(count);},(r,n)->{
            var values=new ArrayList<String>();for(int i=1;i<=columns.size();i++)values.add(r.getString(i));return values;
        });
    }
    public List<List<String>> profileRows(Snapshot snapshot,List<ColumnInfo> columns){
        verify(snapshot,columns);
        return jdbc.query(sampleSql(snapshot.schema(),snapshot.table(),columns),s->{s.setInt(1,OntologyStatistics.LIMIT);s.setMaxRows(OntologyStatistics.LIMIT);s.setFetchSize(100);},(r,n)->{
            var values=new ArrayList<String>();for(int i=1;i<=columns.size();i++)values.add(r.getString(i));return values;
        });
    }
}
