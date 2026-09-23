package com.dbcompanion.repository;

import com.dbcompanion.common.db.ExternalAddress;
import com.dbcompanion.model.MountedCatalogs.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

@Repository
public class MountedCatalogsRepository {
    public static final String VIEW="USER_MOUNTED_CATALOGS";
    public static final List<String> DETAIL_FIELDS=List.of("CATALOG_ID","DESCRIPTION","DEFAULT_SCHEMA",
        "IS_SYNCHRONIZED","CACHE_ENABLED","CACHE_DURATION","CACHE_ASYNC","DATA_CATALOG_TYPE",
        "CREATED","CREATED_AT","CREATED_BY","LAST_UPDATED","LAST_UPDATED_AT",
        "DB_LINK","DATABASE_LINK","ENDPOINT","CREDENTIAL_OWNER","CREDENTIAL_NAME",
        "CATALOG_CREDENTIAL","DATA_STORAGE_CREDENTIAL");
    private final JdbcTemplate jdbc;
    public MountedCatalogsRepository(JdbcTemplate source){
        jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(10);
    }
    public record Target(String owner,String name) {
        public Target { identifier(owner);identifier(name); }
        public String sql(){return quote(owner)+"."+quote(name);}
        public String label(){return owner+"."+name;}
    }
    public record Shape(Target target,AgentViewColumns columns) {}
    private static String quote(String value){return "\""+value.replace("\"","\"\"")+"\"";}
    private static void identifier(String value){
        if(value==null||value.isBlank()||value.length()>128||value.indexOf('\0')>=0)throw new UnsupportedShape();
    }
    // Resolve only Oracle's public local synonym, not a same-named object in CURRENT_SCHEMA.
    private Target target(String name){
        var targets=jdbc.query("SELECT TABLE_OWNER, TABLE_NAME, DB_LINK FROM SYS.ALL_SYNONYMS WHERE OWNER = 'PUBLIC' AND SYNONYM_NAME = ?",
            (r,n)->{if(r.getString(3)!=null)throw new UnsupportedShape();return new Target(r.getString(1),r.getString(2));},name);
        if(targets.size()>1)throw new UnsupportedShape();
        return targets.isEmpty()?new Target("SYS",name):targets.getFirst();
    }
    public Target catalogApiTarget(){return target("DBMS_CATALOG");}
    private Shape shape(Target target){
        var columns=jdbc.query("SELECT * FROM "+target.sql()+" WHERE 1=0",(ResultSetExtractor<AgentViewColumns>)r->{
            var metadata=r.getMetaData();var names=new ArrayList<String>();
            for(int i=1;i<=metadata.getColumnCount();i++)names.add(metadata.getColumnName(i));
            return new AgentViewColumns(names);
        });
        for(String field:List.of("CATALOG_NAME","CATALOG_TYPE","IS_ENABLED"))if(columns.optional(field)==null)throw new UnsupportedShape();
        return new Shape(target,columns);
    }
    public static String listSql(Shape shape){
        var c=shape.columns();return "SELECT "+c.required("CATALOG_NAME")+", "+c.required("CATALOG_TYPE")+", "+c.required("IS_ENABLED")
            +" FROM "+shape.target().sql()+" ORDER BY "+c.required("CATALOG_NAME")+" FETCH FIRST 5001 ROWS ONLY";
    }
    public static List<String> detailFields(AgentViewColumns columns){
        return DETAIL_FIELDS.stream().filter(field->columns.optional(field)!=null).toList();
    }
    public static String detailSql(Shape shape){
        var projection=new ArrayList<String>();for(String field:List.of("CATALOG_NAME","CATALOG_TYPE","IS_ENABLED"))projection.add(shape.columns().required(field));
        for(String field:detailFields(shape.columns()))projection.add(shape.columns().required(field));
        return "SELECT "+String.join(", ",projection)+" FROM "+shape.target().sql()+" WHERE "+shape.columns().required("CATALOG_NAME")+" = ? FETCH FIRST 2 ROWS ONLY";
    }
    public Catalog list(){
        String source=VIEW;List<Entry> items=List.of();String status="AVAILABLE",error="";
        try{
            var target=target(VIEW);source=target.label();var shape=shape(target);
            items=jdbc.query(listSql(shape),(r,n)->new Entry(r.getString(1),r.getString(2),r.getString(3)));
            if(items.size()>5000)throw new LimitExceeded();
        }catch(RuntimeException ex){items=List.of();status=status(ex);error=ExternalSourcesRepository.error(ex);}
        return new Catalog(items,source,status,error,api());
    }
    private Api api(){
        String source="SYS.ALL_PROCEDURES";
        try{
            var target=target("DBMS_CATALOG");
            var methods=jdbc.query("SELECT DISTINCT PROCEDURE_NAME FROM SYS.ALL_PROCEDURES WHERE OWNER = ? AND OBJECT_NAME = ? AND PROCEDURE_NAME IN ('GET_SCHEMAS','GET_TABLES','GET_COLUMNS') ORDER BY PROCEDURE_NAME",
                (r,n)->r.getString(1),target.owner(),target.name());
            return new Api(methods.isEmpty()?"NOT_VISIBLE":"VISIBLE",methods,source,"");
        }catch(RuntimeException ex){return new Api(status(ex),List.of(),source,ExternalSourcesRepository.error(ex));}
    }
    public Detail detail(Entry entry){
        var shape=shape(target(VIEW));var names=detailFields(shape.columns());
        var rows=jdbc.query(detailSql(shape),(r,n)->{
            var fields=new ArrayList<Field>();for(int i=0;i<names.size();i++){
                String key=names.get(i);fields.add(new Field(key,displayValue(key,r.getString(i+4))));
            }
            return new Detail(new Entry(r.getString(1),r.getString(2),r.getString(3)),fields,shape.target().label());
        },entry.name());
        if(rows.size()!=1||!rows.getFirst().entry().equals(entry))throw new com.dbcompanion.model.ExternalSources.Failure(409,
            com.dbcompanion.common.i18n.UiMessages.text("catalog.changed","등록 정보가 변경되었습니다. 목록을 새로고침해 주세요."));
        return rows.getFirst();
    }
    public static String displayValue(String key,String value){
        if(!DETAIL_FIELDS.contains(key))throw new IllegalArgumentException("Field not allowed");
        if(key.contains("CREDENTIAL")&&value!=null&&!value.matches("[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*)?") )return "[redacted]";
        if(!key.equals("ENDPOINT"))return value;
        return value==null||value.isBlank()?"":value.contains("://")?ExternalAddress.location(value):"[redacted]";
    }
    public static String status(Throwable ex){
        return ex instanceof UnsupportedShape?"UNSUPPORTED":ex instanceof LimitExceeded?"LIMIT":ExternalSourcesRepository.status(ex);
    }
    private static final class UnsupportedShape extends RuntimeException {}
    private static final class LimitExceeded extends RuntimeException {}
}
