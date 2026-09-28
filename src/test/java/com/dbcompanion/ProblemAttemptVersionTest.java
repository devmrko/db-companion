package com.dbcompanion;
import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.AppRecordRepository;
import com.dbcompanion.service.ProblemQuestionService;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

class ProblemAttemptVersionTest {
    @Test void expectedVersionIsCheckedAfterParentLockInSameWriteTransaction(){
        var source=new SessionDataSource(){@Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);default->empty(m.getReturnType());});}};
        String id="11111111-1111-4111-8111-111111111111";Instant original=Instant.parse("2026-01-01T00:00:00Z");var json=new JsonMapper();int[] inserts={0},locks={0};
        var records=new AppRecordRepository(new JdbcTemplate(source),json){
            @Override public void require(String schema,String login){}
            @Override public ProblemQuestion.Parent lockProblem(String schema,String key){locks[0]++;return new ProblemQuestion.Parent(1,id,"q","d","e","",ProblemQuestion.Status.RECEIVED,false,original,original.plusSeconds(1));}
            @Override public void begin(String schema,String key,String type,JsonNode payload){inserts[0]++;}
            @Override public boolean finish(String schema,String key,String status,JsonNode payload){return true;}
        };
        var service=new ProblemQuestionService(source,records,json);
        var attempt=new ProblemQuestion.Attempt(1,"",id,"q","","","SELECT 1 FROM DUAL","","APP.P","model","","","","","BATCH_USER_SELECTED","SUCCEEDED",null,0,null,null,null,null);
        try(var s=new OntologyReadCacheTest().session()){assertThatThrownBy(()->service.saveAttempt(s,attempt,original)).isInstanceOf(AiAssistant.Failure.class);}
        assertThat(locks[0]).isEqualTo(1);assertThat(inserts[0]).isZero();
    }
}
