package com.dbcompanion.repository;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class MetadataGraphRepositoryJsonTest {
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);
    }

    @Test void graphJsonColumnsUseTypedJdbcReadAndBecomeJsonValuesOnce() throws SQLException {
        var names=List.of("SOURCE_ID","EDGE_ID","TARGET_ID","MAPPING_COUNT");
        var types=List.of("JSON","JSON","VARCHAR2","NUMBER");
        var values=List.of("\"source\"","{\"part\":2}","target",7);
        var metadata=proxy(ResultSetMetaData.class,(self,method,args)->switch(method.getName()){
            case "getColumnCount" -> names.size();
            case "getColumnLabel" -> names.get((int)args[0]-1);
            case "getColumnTypeName" -> types.get((int)args[0]-1);
            default -> throw new AssertionError(method.getName());
        });
        int[] next={0};
        var result=proxy(ResultSet.class,(self,method,args)->switch(method.getName()){
            case "getMetaData" -> metadata;
            case "next" -> next[0]++==0;
            case "getObject" -> {
                int column=(int)args[0]-1;
                if("JSON".equals(types.get(column))){
                    if(args.length!=2||args[1]!=String.class)throw new SQLException("ORA-18722");
                }else if(args.length!=1)throw new AssertionError("Non-JSON column used a typed read");
                yield values.get(column);
            }
            default -> throw new AssertionError(method.getName());
        });
        var rows=MetadataGraphRepository.readRows(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("SOURCE_ID").toString()).isEqualTo("\"source\"");
        assertThat(rows.getFirst().get("EDGE_ID").toString()).isEqualTo("{\"part\":2}");
        assertThat(rows.getFirst().get("TARGET_ID")).isEqualTo("target");
        assertThat(rows.getFirst().get("MAPPING_COUNT")).isEqualTo(7);
        assertThat(new JsonMapper().writeValueAsString(rows))
            .isEqualTo("[{\"SOURCE_ID\":\"source\",\"EDGE_ID\":{\"part\":2},\"TARGET_ID\":\"target\",\"MAPPING_COUNT\":7}]");
    }
}
