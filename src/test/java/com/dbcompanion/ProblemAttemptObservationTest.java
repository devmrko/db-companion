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

class ProblemAttemptObservationTest {
    @Test void serverGeneratedAttemptKeepsExecutionTimeInsteadOfSaveTime(){
        var source=new SessionDataSource(){@Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);default->empty(m.getReturnType());});}};
        String id="11111111-1111-4111-8111-111111111111";
        Instant parentVersion=Instant.parse("2026-01-01T00:00:00Z"),requestedAt=parentVersion.plusSeconds(60);
        var json=new JsonMapper();var payloads=new ArrayList<JsonNode>();
        var records=new AppRecordRepository(new JdbcTemplate(source),json){
            @Override public void require(String schema,String login){}
            @Override public ProblemQuestion.Parent lockProblem(String schema,String key){return new ProblemQuestion.Parent(1,id,"q","d","e","",ProblemQuestion.Status.RECEIVED,false,parentVersion,parentVersion);}
            @Override public void begin(String schema,String key,String type,JsonNode payload){payloads.add(payload);}
            @Override public boolean finish(String schema,String key,String status,JsonNode payload){return true;}
        };
        var service=new ProblemQuestionService(source,records,json);
        var attempt=new ProblemQuestion.Attempt(1,"",id,"q","","SELECT 1 FROM DUAL","SELECT 1 FROM DUAL","","APP.P","model","","","","","BATCH_USER_SELECTED","SUCCEEDED",requestedAt,2345,null,null,null,null);
        try(var s=new OntologyReadCacheTest().session()){service.saveAttempt(s,attempt,parentVersion);}
        assertThat(payloads).hasSize(1);
        var saved=json.treeToValue(payloads.getFirst(),ProblemQuestion.Attempt.class);
        assertThat(saved.capturedAt()).isEqualTo(requestedAt);
        assertThat(saved.elapsedMillis()).isEqualTo(2345);
    }
    @Test void manualAttemptCannotClaimBatchCaptureProvenance(){
        var source=new SessionDataSource(){@Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);default->empty(m.getReturnType());});}};
        String id="11111111-1111-4111-8111-111111111111";Instant version=Instant.parse("2026-01-01T00:00:00Z");
        var json=new JsonMapper();var payloads=new ArrayList<JsonNode>();
        var records=new AppRecordRepository(new JdbcTemplate(source),json){
            @Override public void require(String schema,String login){}
            @Override public ProblemQuestion.Parent lockProblem(String schema,String key){return new ProblemQuestion.Parent(1,id,"q","d","e","",ProblemQuestion.Status.RECEIVED,false,version,version);}
            @Override public void begin(String schema,String key,String type,JsonNode payload){payloads.add(payload);}
            @Override public boolean finish(String schema,String key,String status,JsonNode payload){return true;}
        };
        var service=new ProblemQuestionService(source,records,json);
        var supplied=new ProblemQuestion.Attempt(1,"",id,"q","","response","","","APP.P","model","","","","","BATCH_USER_SELECTED","CAPTURED",Instant.EPOCH,9,null,null,null,null);
        try(var s=new OntologyReadCacheTest().session()){service.saveAttempt(s,supplied);}
        var saved=json.treeToValue(payloads.getFirst(),ProblemQuestion.Attempt.class);
        assertThat(saved.snapshotKind()).isEqualTo("MANUAL_ENTRY");assertThat(saved.availability()).isEqualTo("USER_SUPPLIED");
        assertThat(saved.capturedAt()).isAfter(Instant.EPOCH);assertThat(saved.elapsedMillis()).isEqualTo(9);
    }
}
