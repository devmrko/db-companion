package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.controller.SelectAiResultArchiveController;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.AppRecordRepository;
import com.dbcompanion.service.OntologyInquiry;
import com.dbcompanion.service.SelectAiResultArchiveService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real controller/service/capture path with a synthetic record store; actual Oracle is a separate rehearsal. */
class SelectAiResultArchiveTest {
    private String storage="READY";
    private int probes,writes;
    private boolean finish=true;
    private final Map<String,JsonNode> saved=new HashMap<>();
    private final JsonMapper json=new JsonMapper();
    private final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
            case "getAutoCommit" -> true;
            case "isClosed","isReadOnly" -> false;
            case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
            case "prepareStatement","createStatement" -> throw new AssertionError("No query or AI execution allowed");
            default -> m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
        });
    }};
    private final AppRecordRepository records=new AppRecordRepository(new JdbcTemplate(source),json){
        @Override public String status(String schema,String login){probes++;assertThat(schema).isEqualTo(login);return storage;}
        @Override public void begin(String schema,String id,String type,JsonNode payload){assertThat(type).isEqualTo(SelectAiResultArchive.TYPE);writes++;saved.put(id,payload);}
        @Override public boolean finish(String schema,String id,String state,JsonNode payload){return finish;}
        @Override public QueryArchive.Detail detail(String schema,String id,String type){var payload=saved.get(id);return payload==null?null:new QueryArchive.Detail(new QueryArchive.Item("1",id,"SUCCEEDED",schema,"now","now","q"),payload);}
        @Override public SelectAiResultArchive.Page selectAiResults(String schema,AiExecutionHistory.Query query){return new SelectAiResultArchive.Page(List.of(),query.page(),false);}
    };
    private final SelectAiResultArchiveService service=new SelectAiResultArchiveService(source,records,json);
    private final SelectAiResultArchiveController controller=new SelectAiResultArchiveController(service);
    private PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));return s;}
    private MockHttpServletRequest request(PoolSession s){var r=new MockHttpServletRequest();r.getSession().setAttribute(PoolSession.ATTRIBUTE,s);return r;}
    private void execution(PoolSession session,List<List<OntologyInquiry.Cell>> rows){
        var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"test","test","v1");
        var state=session.metadata().aiTest();
        state.finish(new SelectAiTest.Outcome("generation",SelectAiTest.Action.SQL,profile,"question",Instant.EPOCH,5,"SELECT 1 FROM DUAL",null,null,"complete"));
        state.finishExecution(new SelectAiTest.ExecutionResult("generation",new OntologyInquiry.Rows("generation","SELECT 1 FROM DUAL","hash","APP","2026-11-27T01:00:00Z",List.of("VALUE"),rows,false),null,null,7));
    }
    @Test void exactRowsPersistAcrossFreshSessionsWithoutAnotherQueryAndDuplicateSaveIsIdempotent(){
        String id;
        try(var s=session()){
            execution(s,List.of(List.of(new OntologyInquiry.Cell(null,false)),List.of(new OntologyInquiry.Cell("<b>literal</b>",true))));
            id=service.save(s,"generation",true);assertThat(service.save(s,"generation",true)).isEqualTo(id);assertThat(writes).isEqualTo(1);
            assertThat(service.detail(s,id).data()).isEqualTo(s.metadata().aiTest().executionResult().data());
        }
        try(var fresh=session()){
            assertThat(fresh.metadata().aiTest().executionResult()).isNull();
            var retained=service.detail(fresh,id);assertThat(retained.question()).isEqualTo("question");
            assertThat(retained.data().rows().getFirst().getFirst().value()).isNull();
            assertThat(retained.data().rows().get(1).getFirst().value()).isEqualTo("<b>literal</b>");
            assertThat(retained.data().rows().get(1).getFirst().truncated()).isTrue();
        }
    }
    @Test void emptyResultIsARealSuccessfulSnapshot(){try(var s=session()){execution(s,List.of());assertThat(service.detail(s,service.save(s,"generation",true)).data().rows()).isEmpty();}}
    @Test void missingFailedAndStaleExecutionsAreNeverInventedOrSaved(){try(var s=session()){
        assertThatThrownBy(()->service.save(s,"generation",true)).isInstanceOf(AiAssistant.Failure.class);
        execution(s,List.of());assertThatThrownBy(()->service.save(s,"other",true)).isInstanceOf(AiAssistant.Failure.class);
        s.metadata().aiTest().finishExecution(new SelectAiTest.ExecutionResult("generation",null,"failed","ORA-01031",5));
        assertThatThrownBy(()->service.save(s,"generation",true)).isInstanceOf(AiAssistant.Failure.class);assertThat(writes).isZero();
    }}
    @Test void noConsentAndUnreadyStorageDoNotWrite(){try(var s=session()){
        execution(s,List.of());var r=request(s);
        assertThat(controller.save(new SelectAiResultArchiveController.Save("generation",false),r).getStatusCode().value()).isEqualTo(400);assertThat(probes).isZero();
        for(String state:List.of("MISSING","MISMATCH","UNAVAILABLE")){storage=state;assertThat(controller.save(new SelectAiResultArchiveController.Save("generation",true),r).getStatusCode().value()).isEqualTo(409);}
        assertThat(writes).isZero();
    }}
    @Test void scopeAndAuthenticationAreEnforcedBeforeReads(){
        assertThat(controller.list("","","","","1",new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        try(var s=session()){s.metadata().selectSchema("OTHER");var response=controller.list("","","","","1",request(s));assertThat(response.getStatusCode().value()).isEqualTo(403);assertThat(probes).isZero();}
    }
    @Test void invalidAndMissingIdsAndUnknownDatesAreDistinct(){try(var s=session()){
        var r=request(s);assertThat(controller.detail("invalid",r).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.detail("11111111-1111-4111-8111-111111111111",r).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.list("2026-01-01","2026-11-27","","","1",r).getStatusCode().value()).isEqualTo(400);
        var result=controller.list("","","","","1",r);assertThat(result.getStatusCode().value()).isEqualTo(200);assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
    }}
    @Test void incompleteWriteCannotClaimSuccessOrDiscardCurrentResult(){try(var s=session()){
        execution(s,List.of());finish=false;assertThat(controller.save(new SelectAiResultArchiveController.Save("generation",true),request(s)).getStatusCode().value()).isEqualTo(409);assertThat(s.metadata().aiTest().executionResult().data()).isNotNull();
    }}
}
