package com.dbcompanion;

import com.dbcompanion.repository.VpdManagementRepository;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import static org.assertj.core.api.Assertions.*;

class VpdObjectRepositoryTest {
    final List<String> queries=new ArrayList<>();
    final List<Map<Integer,String>> bindings=new ArrayList<>();
    List<Map<String,String>> objects=List.of(Map.of("OBJECT_NAME","T","OBJECT_TYPE","TABLE","OWNED","Y"),Map.of("OBJECT_NAME","V","OBJECT_TYPE","VIEW","OWNED","N"));
    final AbstractDataSource source=new AbstractDataSource(){
        @Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->m.getName().equals("prepareStatement")?statement((String)a[0]):empty(m.getReturnType()));}
        @Override public Connection getConnection(String user,String password){throw new AssertionError("Unexpected connection");}
    };
    final VpdManagementRepository repository=new VpdManagementRepository(new JdbcTemplate(source));
    @Test void listsTablesAndViewsWithOwnershipAndObjectGrantSources(){
        var result=repository.objects("APP");assertThat(result).hasSize(2);
        assertThat(result.get(0).owned()).isTrue();assertThat(result.get(0).privileges()).isEmpty();
        assertThat(result.get(1).type()).isEqualTo("VIEW");assertThat(result.get(1).owned()).isFalse();
        assertThat(result.get(1).privileges()).containsExactly("READ","SELECT","UPDATE");assertThat(result.get(1).grantSources()).containsExactly("DIRECT","ROLE","PUBLIC");
        assertThat(queries.get(0)).contains("OBJECT_TYPE IN ('TABLE','VIEW')","SUBOBJECT_NAME IS NULL","SYS_CONTEXT('USERENV','SESSION_USER')");
        assertThat(queries.get(1)).contains("SYS.ALL_TAB_PRIVS","TABLE_SCHEMA = ?","GRANTEE IN (SYS_CONTEXT('USERENV','SESSION_USER'),'PUBLIC')","SYS.SESSION_ROLES").doesNotContain("GRANTOR =");
        assertThat(bindings).allSatisfy(args->assertThat(args).containsExactlyEntriesOf(Map.of(1,"APP")));
    }
    @Test void ownerIsBoundAndLegacyNamesEndpointAlsoIncludesViews(){
        String schema="APP' OR 1=1 --";assertThat(repository.tables(schema)).containsExactly("T","V");
        assertThat(queries).allSatisfy(sql->assertThat(sql).doesNotContain(schema));
        assertThat(bindings).allSatisfy(args->assertThat(args).containsEntry(1,schema));
    }
    @Test void viewSnapshotPreservesTypeAndLoadsItsColumnsAndPolicies(){
        objects=List.of(Map.of("OBJECT_ID","42","OBJECT_TYPE","VIEW","LAST_DDL_TIME","2026-09-28","STATUS","VALID"));
        var snapshot=repository.snapshot("APP","V");assertThat(snapshot.objects().getFirst()).containsEntry("OBJECT_TYPE","VIEW");
        assertThat(queries).hasSize(5);assertThat(queries.getFirst()).contains("OBJECT_TYPE IN ('TABLE','VIEW')");
        assertThat(queries).anySatisfy(sql->assertThat(sql).contains("SYS.ALL_TAB_COLUMNS"));
        assertThat(bindings).allSatisfy(args->assertThat(args).containsEntry(1,"APP").containsEntry(2,"V"));
    }
    @Test void invisibleAndOverLimitObjectsDoNotReturnPartialTargets(){
        objects=List.of();assertThatThrownBy(()->repository.snapshot("APP","V")).hasMessage("vpd.tableMissing");
        objects=Collections.nCopies(5001,Map.of("OBJECT_NAME","T","OBJECT_TYPE","TABLE","OWNED","Y"));
        assertThatThrownBy(()->repository.objects("APP")).hasMessage("vpd.limit");
    }
    private PreparedStatement statement(String sql){
        queries.add(sql);var args=new HashMap<Integer,String>();bindings.add(args);
        return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()){
            case "setString" -> {args.put((Integer)a[0],(String)a[1]);yield null;}
            case "setQueryTimeout" -> {assertThat(a[0]).isEqualTo(15);yield null;}
            case "executeQuery" -> results(sql.contains("SYS.ALL_OBJECTS")?objects:sql.contains("SYS.ALL_TAB_PRIVS")?List.of(Map.of("TABLE_NAME","V","PRIVILEGES","READ,SELECT,UPDATE","DIRECT_GRANT","1","ROLE_GRANT","1","PUBLIC_GRANT","1")):List.of());
            default -> empty(m.getReturnType());
        });
    }
    private ResultSet results(List<Map<String,String>> rows){
        List<String> keys=rows.isEmpty()?List.of():new ArrayList<>(rows.getFirst().keySet());int[] index={-1};
        return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
            case "next" -> ++index[0]<rows.size();
            case "getString" -> rows.get(index[0]).get(keys.get((Integer)a[0]-1));
            case "getMetaData" -> proxy(ResultSetMetaData.class,(x,f,b)->switch(f.getName()){
                case "getColumnCount" -> keys.size();case "getColumnLabel" -> keys.get((Integer)b[0]-1);default -> empty(f.getReturnType());
            });
            default -> empty(m.getReturnType());
        });
    }
    private static Object empty(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;return null;}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
}
