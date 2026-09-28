package com.dbcompanion;
import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.AppRecordRepository;
import com.dbcompanion.service.ProblemQuestionService;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

class ProblemRetentionServiceTest {
    @Test void retentionChangeIsPersistedAndThenBlocksDeletionBeforeAnyDeleteStatement(){
        String id="11111111-1111-4111-8111-111111111111";Instant time=Instant.parse("2026-01-01T00:00:00Z");
        var current=new AtomicReference<>(new ProblemQuestion.Parent(1,id,"q","d","e","",ProblemQuestion.Status.RECEIVED,false,time,time));
        var source=new SessionDataSource(){@Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;case "prepareStatement"->throw new AssertionError("No physical SQL should be attempted by a retained delete");case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);default->empty(m.getReturnType());});}};
        var json=new JsonMapper();
        var records=new AppRecordRepository(new JdbcTemplate(source),json){
            @Override public ProblemQuestion.Parent lockProblem(String schema,String key){assertThat(key).isEqualTo(id);return current.get();}
            @Override public void updateProblem(String schema,ProblemQuestion.Parent next,Instant expected){assertThat(expected).isEqualTo(time);current.set(next);}
            @Override public ProblemQuestion.DeletePreview problemDeletePreview(String schema,List<String> ids){return new ProblemQuestion.DeletePreview(ids,1,0,"synthetic-fingerprint");}
        };
        var service=new ProblemQuestionService(source,records,json);
        try(var s=new OntologyReadCacheTest().session()){
            service.update(s,id,new ProblemQuestion.Update(ProblemQuestion.Status.UNDER_REVIEW,true),time);
            assertThat(current.get().retained()).isTrue();
            assertThatThrownBy(()->service.delete(s,List.of(id),1,0,"synthetic-fingerprint",true))
              .isInstanceOf(AiAssistant.Failure.class).hasMessageContaining("보존");
        }
    }
}
