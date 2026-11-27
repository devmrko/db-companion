package com.dbcompanion;

import com.dbcompanion.model.AiCreation;
import com.dbcompanion.repository.AiCreationRepository;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import static org.assertj.core.api.Assertions.*;

class AiCreationBindingTest {
    record Binding(String method,int index,Object value) {}
    @Test void optionalEmptyDescriptionBindsNullWhileNonemptyDescriptionUsesAReader(){
        for(String description:List.of("","교육용 프로파일")){
            var calls=new ArrayList<Binding>();
            var statement=(PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(proxy,method,args)->{
                if(Set.of("setClob","setNull","setString").contains(method.getName())){
                    if(method.getName().equals("setClob"))assertThat(((Number)args[2]).longValue()).isPositive();
                    calls.add(new Binding(method.getName(),(int)args[0],args[1]));
                }
                return method.getReturnType()==int.class?1:method.getReturnType()==boolean.class?false:null;
            });
            var connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->switch(method.getName()){
                case "prepareStatement"->statement;case "isClosed"->false;default->null;
            });
            var data=new AbstractDataSource(){@Override public Connection getConnection(){return connection;}@Override public Connection getConnection(String u,String p){return connection;}};
            var repository=new AiCreationRepository(new JdbcTemplate(data),null,null,null,null,null,null);
            repository.create("C##CLOUD$SERVICE",new AiCreation.Input("APP",AiCreation.Kind.PROFILE,"","a".repeat(64),"P",description,"ENABLED","{\"provider\":\"oci\"}",false));
            var desc=calls.stream().filter(v->v.index()==2).findFirst().orElseThrow();
            assertThat(desc.method()).isEqualTo(description.isEmpty()?"setNull":"setClob");
            if(description.isEmpty())assertThat(desc.value()).isEqualTo(Types.CLOB);
            assertThat(calls).anyMatch(v->v.method().equals("setString")&&v.index()==3&&v.value().equals("P"));
        }
    }
}
