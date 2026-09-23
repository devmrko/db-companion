package com.dbcompanion.repository;

import com.dbcompanion.model.CatalogColumnProbe.*;
import com.dbcompanion.model.CatalogColumnProbe;
import com.dbcompanion.model.CatalogOperations.Grid;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.CallableStatementCreator;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@Profile("catalog-diagnostics")
public class CatalogColumnProbeRepository {
    public static final List<String> FIELDS=List.of("OWNER","TABLE_NAME","COLUMN_NAME","COLUMN_ID","DATA_TYPE","DATA_LENGTH","DATA_PRECISION","DATA_SCALE","NULLABLE");
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public CatalogColumnProbeRepository(JdbcTemplate source,JsonMapper json){
        jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(10);jdbc.setMaxRows(101);jdbc.setFetchSize(50);
        this.json=json;
    }
    public static List<String> fields(Step step){
        return switch(step){
            case LINK_COMMENTS->List.of("OWNER","TABLE_NAME","COLUMN_NAME","COMMENTS");
            case MYSQL_COLUMNS->List.of("TABLE_SCHEMA","TABLE_NAME","COLUMN_NAME","ORDINAL_POSITION","COLUMN_TYPE","COLUMN_COMMENT");
            case MYSQL_TABLES->List.of("TABLE_SCHEMA","TABLE_NAME","TABLE_COMMENT");
            case MYSQL_PASSTHROUGH->List.of("TABLE_SCHEMA","TABLE_NAME","COLUMN_NAME","ORDINAL_POSITION","COLUMN_TYPE","COLUMN_COMMENT","TABLE_COMMENT","EXTRA");
            case MYSQL_COMMENT_LENGTHS->List.of("TABLE_SCHEMA","TABLE_NAME","COLUMN_NAME","COLUMN_COMMENT_CHARS","TABLE_COMMENT_CHARS");
            default->FIELDS;
        };
    }
    public static String sql(Step step,MountedCatalogsRepository.Target api,Target target){
        if(step==Step.MYSQL_PASSTHROUGH||step==Step.MYSQL_COMMENT_LENGTHS)return passthroughSql(step,target);
        String projection=String.join(", ",FIELDS);
        if(step==Step.LINK_DICTIONARY)return "SELECT "+projection+" FROM ALL_TAB_COLUMNS@\""+target.link()+"\" WHERE OWNER = ? AND TABLE_NAME = ? AND ROWNUM <= 101 ORDER BY COLUMN_ID";
        if(step==Step.LINK_COMMENTS)return "SELECT "+String.join(", ",fields(step))+" FROM ALL_COL_COMMENTS@\""+target.link()+"\" WHERE OWNER = ? AND TABLE_NAME = ? AND ROWNUM <= 101 ORDER BY COLUMN_NAME";
        if(step==Step.MYSQL_COLUMNS||step==Step.MYSQL_TABLES){
            String nativeProjection=String.join(", ",fields(step).stream().map(f->"\""+f+"\"").toList());
            String view=step==Step.MYSQL_COLUMNS?"COLUMNS":"TABLES";
            return "SELECT "+nativeProjection+" FROM \"information_schema\".\""+view+"\"@\""+target.link()+"\" WHERE \"TABLE_NAME\" = ? AND ROWNUM <= 101 ORDER BY \"TABLE_SCHEMA\""
                +(step==Step.MYSQL_COLUMNS?", \"ORDINAL_POSITION\"":"");
        }
        return "SELECT "+projection+" FROM TABLE("+api.sql()+".GET_COLUMNS(catalog_name => ?, schema_name => ?, table_name => ?, "
                +(step==Step.PARENT_TYPE?"parent_type => 'TABLE', ":"")+"result_limit => 101)) ORDER BY COLUMN_ID";
    }
    public static List<String> binds(Step step,Target target){
        return switch(step){
            case LINK_DICTIONARY, LINK_COMMENTS->List.of(target.schema(),target.table());
            case MYSQL_COLUMNS, MYSQL_TABLES, MYSQL_PASSTHROUGH, MYSQL_COMMENT_LENGTHS->List.of(target.table());
            case PARENT_TYPE->List.of(target.catalog(),target.schema(),target.table());
            case QUOTED_NAMES->List.of(target.catalog(),CatalogColumnProbe.quoted(target.schema()),CatalogColumnProbe.quoted(target.table()));
        };
    }
    public Grid query(Step step,String sql,List<String> binds){
        if(step==Step.MYSQL_PASSTHROUGH||step==Step.MYSQL_COMMENT_LENGTHS)return passthrough(step,sql,binds);
        var fields=fields(step);
        return jdbc.query(sql,(ResultSetExtractor<Grid>)r->{
            var rows=new ArrayList<Map<String,String>>();
            while(r.next()){
                if(rows.size()==100)throw new IllegalStateException("More than 100 metadata rows; result not retained");
                var row=new LinkedHashMap<String,String>();
                for(int i=0;i<fields.size();i++){
                    String value=r.getString(i+1);if(value!=null&&value.length()>8192)throw new IllegalStateException("Metadata field exceeds 8192 characters");
                    row.put(fields.get(i),value);
                }
                rows.add(row);
            }
            return new Grid(fields,rows);
        },binds.toArray());
    }
    public static String nativeCommentSql(){
        return "SELECT c.TABLE_SCHEMA, c.TABLE_NAME, c.COLUMN_NAME, CAST(c.ORDINAL_POSITION AS CHAR), c.COLUMN_TYPE, c.COLUMN_COMMENT, t.TABLE_COMMENT, c.EXTRA "
            +"FROM information_schema.COLUMNS c JOIN information_schema.TABLES t ON t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME "
            +"WHERE c.TABLE_NAME = ? ORDER BY c.TABLE_SCHEMA, c.ORDINAL_POSITION LIMIT 101";
    }
    public static String nativeCommentLengthsSql(){
        return "SELECT c.TABLE_SCHEMA, c.TABLE_NAME, c.COLUMN_NAME, "
            +"CAST(COALESCE(CHAR_LENGTH(c.COLUMN_COMMENT),-1) AS CHAR(20)), CAST(COALESCE(CHAR_LENGTH(t.TABLE_COMMENT),-1) AS CHAR(20)) "
            +"FROM information_schema.COLUMNS c JOIN information_schema.TABLES t ON t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME "
            +"WHERE c.TABLE_NAME = ? ORDER BY c.TABLE_SCHEMA, c.ORDINAL_POSITION LIMIT 101";
    }
    public static String passthroughSql(Target target){
        return passthroughSql(Step.MYSQL_PASSTHROUGH,target);
    }
    public static String passthroughSql(Step step,Target target){
        if(step!=Step.MYSQL_PASSTHROUGH&&step!=Step.MYSQL_COMMENT_LENGTHS)throw new IllegalArgumentException("Invalid passthrough step");
        String link="@\""+target.link()+"\"";
        var gets=new StringBuilder();var names=fields(step);
        for(int i=0;i<names.size();i++){
            gets.append("v := NULL; DBMS_HS_PASSTHROUGH.GET_VALUE").append(link).append("(c, ").append(i+1).append(", v);\n")
                .append("IF LENGTH(v)>8192 THEN RAISE_APPLICATION_ERROR(-20086,'Metadata field exceeds 8192 characters'); END IF;\n")
                .append("size_chars := size_chars + NVL(LENGTH(v),0);\n")
                .append("IF size_chars>1000000 THEN RAISE_APPLICATION_ERROR(-20086,'Metadata exceeds 1000000 characters'); END IF;\n")
                .append("IF v IS NULL THEN item.put_null('").append(names.get(i)).append("'); ELSE item.put('").append(names.get(i)).append("',v); END IF;\n");
        }
        return """
            DECLARE
              c BINARY_INTEGER := NULL;
              n BINARY_INTEGER;
              v VARCHAR2(32767);
              selected_table VARCHAR2(128) := ?;
              size_chars PLS_INTEGER := 0;
              items SYS.JSON_ARRAY_T := SYS.JSON_ARRAY_T();
              item SYS.JSON_OBJECT_T;
            BEGIN
              c := DBMS_HS_PASSTHROUGH.OPEN_CURSOR%s;
              DBMS_HS_PASSTHROUGH.PARSE%s(c,'%s');
              DBMS_HS_PASSTHROUGH.BIND_VARIABLE%s(c,1,selected_table);
              LOOP
                BEGIN
                  n := DBMS_HS_PASSTHROUGH.FETCH_ROW%s(c);
                EXCEPTION WHEN NO_DATA_FOUND THEN EXIT;
                END;
                EXIT WHEN n=0;
                IF items.get_size()>=100 THEN RAISE_APPLICATION_ERROR(-20086,'More than 100 metadata rows'); END IF;
                item := SYS.JSON_OBJECT_T();
                %s
                items.append(item);
              END LOOP;
              DBMS_HS_PASSTHROUGH.CLOSE_CURSOR%s(c); c := NULL;
              ? := items.to_clob();
            EXCEPTION WHEN OTHERS THEN
              IF c IS NOT NULL THEN
                BEGIN DBMS_HS_PASSTHROUGH.CLOSE_CURSOR%s(c); EXCEPTION WHEN OTHERS THEN NULL; END;
              END IF;
              RAISE;
            END;
            """.formatted(link,link,step==Step.MYSQL_COMMENT_LENGTHS?nativeCommentLengthsSql():nativeCommentSql(),link,link,gets,link,link);
    }
    private Grid passthrough(Step step,String sql,List<String> binds){
        return jdbc.execute((CallableStatementCreator)connection->{
            var call=connection.prepareCall(sql);
            call.setString(1,binds.getFirst());call.registerOutParameter(2,java.sql.Types.CLOB);return call;
        },(CallableStatementCallback<Grid>)call->{
            call.execute();var clob=call.getClob(2);
            if(clob==null)throw new IllegalStateException("Missing native metadata result");
            try{
                if(clob.length()>8_000_000)throw new IllegalStateException("Native metadata JSON exceeds limit");
                return parseNativeComments(step,clob.getSubString(1,(int)clob.length()),json);
            }finally{clob.free();}
        });
    }
    public static Grid parseNativeComments(String value,JsonMapper json){
        return parseNativeComments(Step.MYSQL_PASSTHROUGH,value,json);
    }
    public static Grid parseNativeComments(Step step,String value,JsonMapper json){
        if(step!=Step.MYSQL_PASSTHROUGH&&step!=Step.MYSQL_COMMENT_LENGTHS)throw new IllegalArgumentException("Invalid passthrough step");
        if(value==null||value.length()>8_000_000)throw new IllegalStateException("Invalid native metadata result");
        var root=json.readTree(value);var fields=fields(step);
        if(!root.isArray()||root.size()>100)throw new IllegalStateException("Invalid native metadata rows");
        var rows=new ArrayList<Map<String,String>>();int total=0;
        for(var item:root){
            if(!item.isObject()||item.size()!=fields.size())throw new IllegalStateException("Invalid native metadata fields");
            var row=new LinkedHashMap<String,String>();
            for(String field:fields){
                var node=item.get(field);
                if(node==null||(!node.isNull()&&!node.isString()))throw new IllegalStateException("Invalid native metadata field");
                String text=node.isNull()?null:node.asString();
                if(text!=null){total+=text.length();if(text.length()>8192||total>1_000_000)throw new IllegalStateException("Native metadata text exceeds limit");}
                row.put(field,text);
            }
            if(step==Step.MYSQL_COMMENT_LENGTHS){
                for(String field:List.of("COLUMN_COMMENT_CHARS","TABLE_COMMENT_CHARS")){
                    String length=row.get(field);
                    if(length==null||!length.matches("-1|0|[1-9][0-9]{0,9}"))throw new IllegalStateException("Invalid native comment length");
                }
            }
            rows.add(row);
        }
        return new Grid(fields,rows);
    }
}
