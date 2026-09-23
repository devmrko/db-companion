package com.dbcompanion;

import com.dbcompanion.common.db.OntologySql;
import com.dbcompanion.model.Ontology.Failure;
import com.dbcompanion.repository.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyCatalogRepositoryTest {
    List<String> names=List.of("A","B","C");
    final Map<String,String> comments=Map.of("A","한글 코멘트","C","comment");
    final List<String> sqls=new ArrayList<>();
    final List<List<Object>> bindings=new ArrayList<>();
    final List<Integer> timeouts=new ArrayList<>();
    static Object empty(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;return null;}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
    ResultSet result(List<List<String>> rows,int columns){
        int[] index={-1};
        ResultSetMetaData metadata=proxy(ResultSetMetaData.class,(p,m,a)->m.getName().equals("getColumnCount")?columns:empty(m.getReturnType()));
        return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
            case "next" -> ++index[0]<rows.size();
            case "getString" -> rows.get(index[0]).get((int)a[0]-1);
            case "getMetaData" -> metadata;
            default -> empty(m.getReturnType());
        });
    }
    PreparedStatement statement(String sql){
        sqls.add(sql);var args=new TreeMap<Integer,Object>();
        return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()){
            case "setString","setObject" -> {args.put((int)a[0],a[1]);yield null;}
            case "setQueryTimeout" -> {timeouts.add((int)a[0]);yield null;}
            case "executeQuery" -> {
                bindings.add(new ArrayList<>(args.values()));
                if(sql.contains("SYS.ALL_TABLES"))yield result(names.stream().map(List::of).toList(),1);
                var rows=new ArrayList<List<String>>();
                for(var arg:args.tailMap(2).values())if(comments.containsKey(arg))rows.add(List.of((String)arg,comments.get(arg)));
                yield result(rows,2);
            }
            default -> empty(m.getReturnType());
        });
    }
    final AbstractDataSource source=new AbstractDataSource(){
        @Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->m.getName().equals("prepareStatement")?statement((String)a[0]):empty(m.getReturnType()));}
        @Override public Connection getConnection(String u,String p){throw new AssertionError();}
    };
    final JdbcTemplate jdbc=new JdbcTemplate(source);
    final OntologyRepository repository=new OntologyRepository(jdbc,new JsonMapper(),new DatabaseRepository(jdbc),new TableStructureRepository(jdbc));
    @Test void separateBoundQueriesPreserveOrderAndMissingCommentsWithThirtySecondLimit(){
        var rows=repository.tables("Mixed Owner");
        assertThat(rows).extracting("name").containsExactly("A","B","C");
        assertThat(rows).extracting("description").containsExactly("한글 코멘트",null,"comment");
        assertThat(sqls).hasSize(2).allSatisfy(sql->assertThat(sql).doesNotContain("JOIN","Mixed Owner"));
        assertThat(bindings.getFirst()).containsExactly("Mixed Owner",OntologySql.TABLE);
        assertThat(bindings.getLast()).containsExactly("Mixed Owner","A","B","C");
        assertThat(timeouts).containsExactly(30,30);
    }
    @Test void commentBatchesAreBoundedAndNamesAreNeverInterpolated(){
        names=IntStream.range(0,1201).mapToObj(i->"T'"+i).toList();repository.tables("APP");
        assertThat(bindings).extracting(List::size).containsExactly(2,501,501,202);
        assertThat(sqls).allSatisfy(sql->assertThat(sql).doesNotContain("T'"));
    }
    @Test void excessiveTableCountFailsBeforeCommentLookup(){
        names=IntStream.range(0,5001).mapToObj(i->"T"+i).toList();
        assertThatThrownBy(()->repository.tables("APP")).isInstanceOf(Failure.class);
        assertThat(sqls).hasSize(1);
    }
    @Test void emptySchemaDoesNotIssueEmptyInClause(){names=List.of();assertThat(repository.tables("APP")).isEmpty();assertThat(sqls).hasSize(1);}
}
