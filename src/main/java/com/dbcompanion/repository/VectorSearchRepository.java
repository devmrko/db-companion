package com.dbcompanion.repository;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.model.VectorSearch;
import com.dbcompanion.model.VectorSearch.*;
import java.io.IOException;
import java.sql.Types;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;
import static com.dbcompanion.model.VectorSearch.*;

@Repository
public class VectorSearchRepository {
    private final JdbcTemplate jdbc, searchJdbc;
    public VectorSearchRepository(JdbcTemplate jdbc) {
        var source=Objects.requireNonNull(jdbc.getDataSource());
        this.jdbc=new JdbcTemplate(source); this.jdbc.setQueryTimeout(10);
        this.searchJdbc=new JdbcTemplate(source); this.searchJdbc.setQueryTimeout(30);
    }
    public List<Table> tables(String schema,String login) {
        var vectors=new LinkedHashMap<String,List<String>>(); var comments=new HashMap<String,String>();
        // CURRENT_SCHEMA does not change the authenticated user's USER_* dictionary scope.
        boolean ownSchema=schema.equals(login);
        String sql=ownSchema ? """
                SELECT c.TABLE_NAME, c.COLUMN_NAME, x.COMMENTS
                FROM SYS.USER_TAB_COLUMNS c
                JOIN SYS.USER_TABLES t ON t.TABLE_NAME=c.TABLE_NAME
                LEFT JOIN SYS.USER_TAB_COMMENTS x ON x.TABLE_NAME=t.TABLE_NAME AND x.TABLE_TYPE='TABLE'
                WHERE c.DATA_TYPE='VECTOR'
                ORDER BY c.TABLE_NAME, c.COLUMN_ID
                """ : """
                SELECT c.TABLE_NAME, c.COLUMN_NAME, x.COMMENTS
                FROM SYS.ALL_TAB_COLUMNS c
                JOIN SYS.ALL_TABLES t ON t.OWNER=c.OWNER AND t.TABLE_NAME=c.TABLE_NAME
                LEFT JOIN SYS.ALL_TAB_COMMENTS x ON x.OWNER=t.OWNER AND x.TABLE_NAME=t.TABLE_NAME AND x.TABLE_TYPE='TABLE'
                WHERE c.OWNER=? AND c.DATA_TYPE='VECTOR'
                ORDER BY c.TABLE_NAME, c.COLUMN_ID
                """;
        jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                    vectors.computeIfAbsent(r.getString(1),key->new ArrayList<>()).add(r.getString(2));
                    comments.put(r.getString(1),r.getString(3));
                },ownSchema ? new Object[0] : new Object[]{schema});
        return vectors.entrySet().stream().map(e->new Table(e.getKey(),comments.get(e.getKey()),e.getValue())).toList();
    }
    public List<Column> columns(String schema,String table) {
        var kind=jdbc.query("""
                SELECT t.IOT_TYPE, t.TEMPORARY, t.NESTED,
                  (SELECT COUNT(*) FROM SYS.ALL_EXTERNAL_TABLES e WHERE e.OWNER=t.OWNER AND e.TABLE_NAME=t.TABLE_NAME)
                FROM SYS.ALL_TABLES t WHERE t.OWNER=? AND t.TABLE_NAME=?
                """,(r,i)->r.getString(1)==null && "N".equals(r.getString(2)) && "NO".equals(r.getString(3)) && r.getInt(4)==0,schema,table);
        if(kind.isEmpty()) throw new Failure(404,UiMessages.text("ui.3093f8f809f2", "테이블이 없거나 조회 권한이 없습니다."));
        if(!kind.getFirst()) throw new Failure(422,UiMessages.text("ui.6a1a875840a0", "IOT·외부·임시·중첩 테이블의 행 조회는 아직 지원하지 않습니다."));
        var columns=jdbc.query("""
                SELECT c.COLUMN_NAME, c.DATA_TYPE, x.COMMENTS
                FROM SYS.ALL_TAB_COLUMNS c
                LEFT JOIN SYS.ALL_COL_COMMENTS x ON x.OWNER=c.OWNER AND x.TABLE_NAME=c.TABLE_NAME AND x.COLUMN_NAME=c.COLUMN_NAME
                WHERE c.OWNER=? AND c.TABLE_NAME=? ORDER BY c.COLUMN_ID
                """,(r,i)->new Column(r.getString(1),r.getString(2),r.getString(3)),schema,table);
        if(columns.stream().noneMatch(Column::vector)) throw new Failure(409,UiMessages.text("ui.60c14175846f", "현재 테이블에 VECTOR 컬럼이 없습니다. 목록을 새로고침해 주세요."));
        return columns;
    }
    public List<Model> models() {
        return jdbc.query("SELECT OWNER, MODEL_NAME FROM SYS.ALL_MINING_MODELS WHERE MINING_FUNCTION='EMBEDDING' AND ALGORITHM='ONNX' ORDER BY OWNER, MODEL_NAME",
                (r,i)->new Model(r.getString(1),r.getString(2)));
    }
    public boolean modelAvailable(String owner,String model) {
        return !jdbc.queryForList("SELECT MODEL_NAME FROM SYS.ALL_MINING_MODELS WHERE OWNER=? AND MODEL_NAME=? AND MINING_FUNCTION='EMBEDDING' AND ALGORITHM='ONNX'",
                String.class,owner,model).isEmpty();
    }
    public record Statement(String sql,List<Object> args) {}
    private static String table(Selection s) { return quote(s.schema())+"."+quote(s.table()); }
    public static String valueExpression(Column c) {
        String ref="t."+quote(c.name());
        if(!c.supported()) return "CAST(NULL AS VARCHAR2(1))";
        return switch(c.type()) {
            case "VECTOR" -> "VECTOR_SERIALIZE("+ref+" RETURNING CLOB)";
            case "JSON" -> "JSON_SERIALIZE("+ref+" RETURNING CLOB)";
            case "RAW" -> "RAWTOHEX("+ref+")";
            default -> ref;
        };
    }
    public static Statement rowsStatement(Selection s,List<Column> columns,int page,String vector, String metric,int k) {
        VectorSearch.columns(s,columns); page(page);
        boolean search=vector!=null;
        if(search && (!METRICS.contains(Objects.toString(metric,"")) || k<1 || k>100)) throw invalid(UiMessages.text("ui.ed04d19f060b", "검색 조건을 확인해 주세요."));
        String content="CAST(NULL AS VARCHAR2(1))";
        if(!s.content().isEmpty()) {
            Column c=columns.stream().filter(x->x.name().equals(s.content())).findFirst().orElseThrow();
            String value=valueExpression(c);
            content=switch(c.type()) {
                case "CLOB","NCLOB","JSON" -> "DBMS_LOB.SUBSTR("+value+", 501, 1)";
                case "CHAR","VARCHAR2","NCHAR","NVARCHAR2","RAW" -> "SUBSTR("+value+", 1, 501)";
                default -> value;
            };
        }
        String v="t."+quote(s.vector());
        String sql="SELECT ROWIDTOCHAR(t.ROWID), "+content+", VECTOR_DIMENSION_COUNT("+v+"), VECTOR_DIMENSION_FORMAT("+v+")";
        var args=new ArrayList<Object>();
        if(search) { sql+=", VECTOR_DISTANCE("+v+", TO_VECTOR(?), "+metric+") AS DBC_DISTANCE"; args.add(new SqlParameterValue(Types.CLOB,vector)); }
        sql+=" FROM "+table(s)+" t";
        if(search) { sql+=" WHERE "+v+" IS NOT NULL ORDER BY DBC_DISTANCE, t.ROWID FETCH EXACT FIRST ? ROWS ONLY"; args.add(k); }
        else { sql+=" ORDER BY t.ROWID OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY"; args.add((page-1)*10); }
        return new Statement(sql,List.copyOf(args));
    }
    public Rows rows(Selection s,List<Column> columns,int page,String vector,String metric,int k) {
        var statement=rowsStatement(s,columns,page,vector,metric,k);
        var items=(vector==null ? jdbc : searchJdbc).query(statement.sql(),(r,i)-> {
            String value=r.getString(2); int dim=r.getInt(3); Integer dimensions=r.wasNull()?null:dim;
            return new Row(r.getString(1),clip(value,PREVIEW_LIMIT),value!=null&&value.length()>PREVIEW_LIMIT,dimensions,
                    r.getString(4),vector==null?null:r.getObject(5,Double.class));
        },statement.args().toArray());
        return new Rows(vector==null?items.subList(0,Math.min(10,items.size())):items,page,vector==null&&items.size()>10,vector!=null);
    }
    public static Statement detailStatement(Selection s,List<Column> columns,String id) {
        VectorSearch.columns(s,columns);
        return new Statement("SELECT "+columns.stream().map(VectorSearchRepository::valueExpression).collect(Collectors.joining(", "))
                +" FROM "+table(s)+" t WHERE t.ROWID=CHARTOROWID(?)",List.of(rowId(id)));
    }
    public List<Value> detail(Selection s,List<Column> columns,String id) {
        var statement=detailStatement(s,columns,id);
        var rows=jdbc.query(statement.sql(),(r,i)-> {
            var result=new ArrayList<Value>();
            for(int col=0;col<columns.size();col++) {
                var c=columns.get(col); String value=null;
                if(Set.of("CLOB","NCLOB","JSON","VECTOR").contains(c.type())) {
                    try(var reader=r.getCharacterStream(col+1)) {
                        if(reader!=null) { var buffer=new char[DETAIL_LIMIT+1]; int total=0,n;
                            while(total<buffer.length && (n=reader.read(buffer,total,buffer.length-total))!=-1) total+=n;
                            value=new String(buffer,0,total);
                        }
                    } catch(IOException ex) { throw new java.sql.SQLException("Cannot read selected value",ex); }
                } else if(c.supported()) value=r.getString(col+1);
                result.add(new Value(c.name(),c.type(),clip(value,DETAIL_LIMIT),value!=null&&value.length()>DETAIL_LIMIT,c.supported()));
            }
            return List.copyOf(result);
        },statement.args().toArray());
        if(rows.isEmpty()) throw new Failure(404,UiMessages.text("ui.6061de3d107d", "행을 찾을 수 없습니다. 조회 후 삭제되거나 이동했을 수 있습니다."));
        return rows.getFirst();
    }
    public static Statement embeddingStatement(Search search,String login,String paramsJson) {
        if(!search.external()) return new Statement("SELECT VECTOR_SERIALIZE(VECTOR_EMBEDDING("+quote(search.modelOwner())+"."+quote(search.model())
                +" USING ? AS DATA) RETURNING CLOB) FROM SYS.DUAL",List.of(search.text()));
        return new Statement("SELECT VECTOR_SERIALIZE(SYS.DBMS_VECTOR.UTL_TO_EMBEDDING(?, JSON(?)) RETURNING CLOB) FROM SYS.DUAL",
                List.of(new SqlParameterValue(Types.CLOB,search.text()),paramsJson));
    }
    public String embedding(Search search,String login,String paramsJson) {
        var statement=embeddingStatement(search,login,paramsJson);
        String vector=searchJdbc.queryForObject(statement.sql(),String.class,statement.args().toArray());
        if(vector==null || vector.isBlank()) throw new Failure(422,UiMessages.text("ui.4ff76271f09d", "임베딩 모델이 벡터를 반환하지 않았습니다."));
        return vector;
    }
}
