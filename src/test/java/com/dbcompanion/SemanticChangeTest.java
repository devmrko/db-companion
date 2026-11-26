package com.dbcompanion;

import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.SemanticChange;
import com.dbcompanion.repository.AppRecordRepository;
import com.dbcompanion.service.SemanticChangeService;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SemanticChangeTest {
    static SemanticChangeService immediate(SessionDataSource source){return new SemanticChangeService(source,null,null,new JsonMapper()){
        @Override public <T> SemanticChange.Result change(String owner,String kind,String name,String operation,Object before,Object request,Runnable execute,Supplier<T> readback,Predicate<T> matches){
            execute.run();return new SemanticChange.Result("test",matches.test(readback.get()),"test");
        }
    };}
    final List<String> events=new ArrayList<>();String storage="READY";JsonNode saved;boolean failFinish;
    final SessionDataSource source=new SessionDataSource(){
        @Override public Connection getConnection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->switch(m.getName()){
            case "getAutoCommit"->true;
            case "commit"->{events.add("commit");yield null;}
            case "isClosed","isReadOnly"->false;
            case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;
            case "equals"->p==a[0];
            case "hashCode"->System.identityHashCode(p);
            default->null;
        });}
    };
    final AppRecordRepository records=new AppRecordRepository(new JdbcTemplate(source),new JsonMapper()){
        @Override public String status(String schema,String login){return storage;}
        @Override public void begin(String schema,String id,String type,JsonNode payload){events.add("before");saved=payload;}
        @Override public boolean finish(String schema,String id,String state,JsonNode payload){if(failFinish)throw new IllegalStateException("write failed");events.add(state);saved=payload;return true;}
    };
    final SemanticChangeService service=new SemanticChangeService(source,records,null,new JsonMapper());
    @Test void commitsBeforeCallingNativeAndRecordsVerifiedReadback(){
        var result=service.change("APP","SOURCE","TERMS","CREATE",Map.of("exists",false),Map.of(),()->{
            assertThat(events).containsExactly("before","commit");events.add("native");
        },()->Map.of("exists",true),a->a.get("exists").equals(true));
        assertThat(result.verified()).isTrue();assertThat(events).containsExactly("before","commit","native","SUCCEEDED","commit");
        assertThat(saved.path("before").path("exists").asBoolean()).isFalse();assertThat(saved.path("after").path("exists").asBoolean()).isTrue();
    }
    @Test void missingStorageAbortsBeforeNative(){storage="MISSING";
        assertThatThrownBy(()->service.change("APP","SOURCE","TERMS","CREATE",Map.of(),Map.of(),()->events.add("native"),Map::of,a->true)).isInstanceOf(RuntimeException.class);
        assertThat(events).doesNotContain("native","before");
    }
    @Test void nativeFailureIsNotRetriedAndActualAfterIsPreserved(){
        var result=service.change("APP","SOURCE","TERMS","ATTRIBUTES",Map.of("old",1),Map.of(),()->{events.add("native");throw new IllegalStateException("partial");},()->Map.of("partial",true),a->true);
        assertThat(result.verified()).isFalse();assertThat(Collections.frequency(events,"native")).isEqualTo(1);
        assertThat(events).contains("CHECK_REQUIRED");assertThat(saved.path("after").path("partial").asBoolean()).isTrue();
    }
    @Test void afterWriteFailureLeavesCommittedBeforeAndDoesNotRepeatNative(){failFinish=true;
        assertThatThrownBy(()->service.change("APP","CALLBACK","DBC_GLOSSARY_CONTEXT","UPGRADE",Map.of("version","v3"),Map.of(),()->events.add("native"),()->Map.of("version","v4"),a->true)).isInstanceOf(RuntimeException.class);
        assertThat(events).containsSubsequence("before","commit","native");assertThat(Collections.frequency(events,"native")).isEqualTo(1);
    }
}
