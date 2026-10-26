package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.config.SelectAiExecutionSettings;
import com.dbcompanion.controller.ProblemEvaluationController;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.ProblemEvaluationService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.ProblemEvaluationTest.*;
import static org.assertj.core.api.Assertions.*;

class ProblemEvaluationServiceTest {
    private final JsonMapper json=new JsonMapper();
    private ProblemQuestion.Detail detail=detail("SQL generation response, not executed rows");
    private AiAssistant.Profile profile=PROFILE;
    private JsonNode saved;
    private int calls,writes;
    private boolean failAi,failSave;
    private final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->switch(m.getName()){
            case "getAutoCommit"->true;
            case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;
            case "prepareStatement","createStatement"->throw new AssertionError("No physical DB access");
            default->m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
        });
    }};
    private final AppRecordRepository records=new AppRecordRepository(new JdbcTemplate(source),json){
        @Override public String status(String schema,String login){assertThat(schema).isEqualTo(login);return "READY";}
        @Override public ProblemQuestion.Detail problemDetail(String schema,String id){assertThat(id).isEqualTo(P);return detail;}
        @Override public ProblemQuestion.Parent lockProblem(String schema,String id){return detail.parent();}
        @Override public List<ProblemEvaluation.Saved> evaluations(String schema,String id){return saved==null?List.of():List.of(json.treeToValue(saved,ProblemEvaluation.Saved.class));}
        @Override public QueryArchive.Detail detail(String schema,String id,String type){return saved==null?null:new QueryArchive.Detail(null,saved);}
        @Override public void begin(String schema,String id,String type,JsonNode payload){if(failSave)throw new IllegalStateException("private SQL diagnostic");assertThat(type).isEqualTo(ProblemEvaluation.TYPE);writes++;}
        @Override public boolean finish(String schema,String id,String state,JsonNode payload){saved=payload;return true;}
    };
    private final AiAssistantRepository ai=new AiAssistantRepository(new JdbcTemplate(source)){
        @Override public AiAssistant.Profile profile(AiAssistant.Selection selected){assertThat(selected.owner()).isEqualTo("APP");return profile;}
        @Override public String explain(String owner,String name,String prompt,int timeout){calls++;assertThat(timeout).isEqualTo(300);assertThat(prompt).contains("savedAttempt","not executed rows");if(failAi)throw new IllegalStateException("provider secret diagnostic");return "Review: period filter is absent. Check the glossary and SQL.";}
    };
    private final ProfileHistoryRepository packages=new ProfileHistoryRepository(new JdbcTemplate(source),json,null){@Override public String packageOwner(String user){return "SYS";}};
    private final ProblemEvaluationService service=new ProblemEvaluationService(source,records,ai,packages,json,new SelectAiExecutionSettings(300));
    private PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));s.metadata().assistant().select(PROFILE.selection());return s;}
    @Test void previewDoesNotCallAiAndReviewDoesNotSaveUntilHumanConfirms(){try(var s=session()){
        var plan=service.preview(s,P,A,"Period filters required",Locale.ENGLISH);assertThat(calls).isZero();assertThat(writes).isZero();
        assertThatThrownBy(()->service.review(s,plan.preview().token(),false)).isInstanceOf(AiAssistant.Failure.class);assertThat(calls).isZero();
        var result=service.review(s,plan.preview().token(),true);assertThat(calls).isEqualTo(1);assertThat(writes).isZero();
        assertThatThrownBy(()->service.save(s,result.id(),ProblemEvaluation.Verdict.NEEDS_CHANGE,"period missing",false)).isInstanceOf(IllegalArgumentException.class);
        failSave=true;assertThatThrownBy(()->service.save(s,result.id(),ProblemEvaluation.Verdict.NEEDS_CHANGE,"period missing",true)).isInstanceOf(IllegalStateException.class);
        failSave=false;var value=service.save(s,result.id(),ProblemEvaluation.Verdict.NEEDS_CHANGE,"period missing",true);
        assertThat(value.actor()).isEqualTo("APP");assertThat(writes).isEqualTo(1);assertThat(calls).isEqualTo(1);
        assertThat(service.save(s,result.id(),ProblemEvaluation.Verdict.NEEDS_CHANGE,"period missing",true)).isEqualTo(value);assertThat(writes).isEqualTo(1);
        assertThatThrownBy(()->service.save(s,result.id(),ProblemEvaluation.Verdict.ACCEPTABLE,"changed decision",true)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(service.history(s,P)).hasSize(1);
    }}
    @Test void profileAndEvidenceChangesPreventPaidCall(){try(var s=session()){
        var plan=service.preview(s,P,A,"",Locale.ENGLISH);profile=new AiAssistant.Profile(PROFILE.selection(),"test","changed-model","v2");
        assertThatThrownBy(()->service.review(s,plan.preview().token(),true)).isInstanceOf(AiAssistant.Failure.class);assertThat(calls).isZero();
        profile=PROFILE;String token=service.preview(s,P,A,"",Locale.ENGLISH).preview().token();detail=detail("changed evidence");
        assertThatThrownBy(()->service.review(s,token,true)).isInstanceOf(AiAssistant.Failure.class);assertThat(calls).isZero();
    }}
    @Test void sessionTokenOverrideChangesPreventPaidCall(){try(var s=session()){
        var plan=service.preview(s,P,A,"",Locale.ENGLISH);s.metadata().assistant().maxTokens(200);
        assertThatThrownBy(()->service.review(s,plan.preview().token(),true)).isInstanceOf(AiAssistant.Failure.class);assertThat(calls).isZero();
    }}
    @Test void failureDoesNotLeakDiagnosticsOrAutomaticallyRetry(){try(var s=session()){
        failAi=true;var plan=service.preview(s,P,A,"",Locale.ENGLISH);
        assertThatThrownBy(()->service.review(s,plan.preview().token(),true)).isInstanceOf(AiAssistant.Failure.class).hasMessageNotContaining("provider secret");
        assertThatThrownBy(()->service.review(s,plan.preview().token(),true)).isInstanceOf(AiAssistant.Failure.class);assertThat(calls).isEqualTo(1);assertThat(writes).isZero();
    }}
    @Test void loginAndOwnerScopeAreRequired(){
        var controller=new ProblemEvaluationController(service);var unauthenticated=new MockHttpServletRequest();
        assertThat(controller.history(P,unauthenticated).getStatusCode().value()).isEqualTo(401);
        try(var s=session()){s.metadata().selectSchema("OTHER");assertThatThrownBy(()->service.history(s,P)).isInstanceOf(Ontology.Failure.class);}
    }
}
