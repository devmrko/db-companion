package com.dbcompanion;

import com.dbcompanion.repository.OntologyQueryRepository;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ReadQueryStatementTimeoutTest {
    private static <T>T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler));}
    private static Object defaultValue(Method method){return method.getReturnType()==boolean.class?false:method.getReturnType()==int.class?0:null;}
    @Test void selectAiBudgetDoesNotChangeOtherCallersOrResultLimits() throws Exception {
        var timeouts=new ArrayList<Integer>();var maxRows=new ArrayList<Integer>();var fetchSizes=new ArrayList<Integer>();
        var effectiveTimeouts=new ArrayList<Integer>();
        var executed=new AtomicInteger();var closed=new AtomicInteger();var acquired=new AtomicInteger();
        var metadata=proxy(ResultSetMetaData.class,(p,m,a)->switch(m.getName()){
            case "getColumnCount"->1;case "getColumnTypeName"->"NUMBER";case "getColumnLabel"->"N";default->defaultValue(m);
        });
        var result=proxy(ResultSet.class,(p,m,a)->m.getName().equals("getMetaData")?metadata:defaultValue(m));
        var statement=proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()){
            case "setQueryTimeout"->{timeouts.add((int)a[0]);yield null;}
            case "setMaxRows"->{maxRows.add((int)a[0]);yield null;}
            case "setFetchSize"->{fetchSizes.add((int)a[0]);yield null;}
            case "executeQuery"->{executed.incrementAndGet();effectiveTimeouts.add(timeouts.getLast());yield result;}
            case "close"->{closed.incrementAndGet();yield null;}
            default->defaultValue(m);
        });
        var connection=proxy(Connection.class,(p,m,a)->{
            if(m.getName().equals("prepareStatement")){assertThat(a[0]).isEqualTo("SELECT 1 FROM DUAL");return statement;}
            return defaultValue(m);
        });
        var source=new AbstractDataSource(){
            @Override public Connection getConnection(){acquired.incrementAndGet();return connection;}
            @Override public Connection getConnection(String user,String password){throw new AssertionError();}
        };
        var repository=new OntologyQueryRepository(new JdbcTemplate(source),new JsonMapper());
        repository.execute("id","SELECT 1 FROM DUAL","hash","APP",300);
        repository.execute("id","SELECT 1 FROM DUAL","hash","APP",600);
        repository.execute("id","SELECT 1 FROM DUAL","hash","APP",3600);
        repository.execute("id","SELECT 1 FROM DUAL","hash","APP");
        assertThat(effectiveTimeouts).containsExactly(300,600,3600,15);
        assertThat(maxRows).containsExactly(201,201,201,201);
        assertThat(fetchSizes).containsExactly(50,50,50,50);
        assertThat(executed).hasValue(4);assertThat(closed).hasValue(4);
        for(int timeout:new int[]{0,-1,3601,Integer.MAX_VALUE})assertThatThrownBy(()->repository.execute("id","SELECT 1 FROM DUAL","hash","APP",timeout)).isInstanceOf(IllegalArgumentException.class);
        assertThat(acquired).hasValue(4);
    }
}
