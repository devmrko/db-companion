package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.controller.ProblemQuestionController;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.AppRecordRepository;
import com.dbcompanion.service.ProblemQuestionService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ProblemQuestionStorageTest {
    private String state="READY";
    private boolean failStatus,failList;
    private int probes,reads,writes;
    private final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->switch(m.getName()){
            case "getAutoCommit" -> true;
            case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
            case "prepareStatement","createStatement" -> throw new AssertionError("No real database access in this fixture");
            default -> m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
        });
    }};
    private final AppRecordRepository records=new AppRecordRepository(new JdbcTemplate(source),new JsonMapper()){
        @Override public String status(String schema,String login){probes++;assertThat(schema).isEqualTo(login);if(failStatus)throw new DataAccessResourceFailureException("internal connection diagnostic");return state;}
        @Override public ProblemQuestion.Page problemPage(String schema,String before){reads++;if(failList)throw new DataAccessResourceFailureException("internal SQL diagnostic");return new ProblemQuestion.Page(List.of(),"");}
        @Override public void begin(String schema,String id,String type,JsonNode payload){writes++;throw new AssertionError("No writes expected");}
    };
    private final ProblemQuestionService service=new ProblemQuestionService(source,records,new JsonMapper());
    private final ProblemQuestionController controller=new ProblemQuestionController(service);
    private PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));return s;}
    private MockHttpServletRequest request(PoolSession s){var r=new MockHttpServletRequest();r.getSession().setAttribute(PoolSession.ATTRIBUTE,s);return r;}

    @Test void missingStorageIsASetupStateAndListReturnsConflictWithoutQueryingMissingTable(){try(var s=session()){
        state="MISSING";var request=request(s);
        assertThat(controller.status(request).getBody()).isEqualTo(Map.of("storage","MISSING"));
        var result=controller.list("",request);assertThat(result.getStatusCode().value()).isEqualTo(409);
        assertThat(result.getBody().toString()).contains("DBC_APP_RECORD").doesNotContain("RDF","오류 (선택)");
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(reads).isZero();assertThat(writes).isZero();
    }}
    @Test void readyButEmptyStorageIsNotReportedAsMissing(){try(var s=session()){
        var result=controller.list("",request(s));assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(new ProblemQuestion.Page(List.of(),""));assertThat(reads).isEqualTo(1);assertThat(probes).isEqualTo(1);
    }}
    @Test void existingIncompatibleStorageIsNotTreatedAsAbsent(){try(var s=session()){
        state="MISMATCH";var result=controller.list("",request(s));assertThat(result.getStatusCode().value()).isEqualTo(409);
        assertThat(result.getBody().toString()).contains("구조").doesNotContain("아직 없습니다","RDF");assertThat(reads).isZero();
    }}
    @Test void failedProbeIsNotMisreportedAsMissingAndDoesNotUseInputFieldLabel(){try(var s=session()){
        failStatus=true;var result=controller.list("",request(s));assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().toString()).contains("연결","권한").doesNotContain("아직 없습니다","오류 (선택)","internal");assertThat(reads).isZero();
    }}
    @Test void unknownOrUnavailableStateCannotReachStorageReads(){try(var s=session()){
        for(String value:List.of("UNAVAILABLE","FUTURE_STATE")){state=value;assertThat(controller.list("",request(s)).getStatusCode().value()).isEqualTo(503);}
        assertThat(reads).isZero();
    }}
    @Test void listFailureAfterSuccessfulProbeRemainsAnError(){try(var s=session()){
        failList=true;var result=controller.list("",request(s));assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().toString()).contains("저장소").doesNotContain("오류 (선택)","internal");
    }}
    @Test void missingStorageCannotBeWrittenEvenWhenClientBypassesDisabledButton(){try(var s=session()){
        state="MISSING";var result=controller.save(new ProblemQuestionController.Save("q","d","e","",ProblemQuestion.Status.RECEIVED),request(s));
        assertThat(result.getStatusCode().value()).isEqualTo(409);assertThat(writes).isZero();assertThat(reads).isZero();
    }}
    @Test void missingStorageDetailAlsoProvidesSetupGuidance(){try(var s=session()){
        state="MISSING";var result=controller.detail("11111111-1111-4111-8111-111111111111",request(s));
        assertThat(result.getStatusCode().value()).isEqualTo(409);assertThat(result.getBody().toString()).contains("DBC_APP_RECORD");
    }}
    @Test void selectedOtherSchemaIsRejectedBeforeStorageProbe(){try(var s=session()){
        s.metadata().selectSchema("OTHER");assertThat(controller.list("",request(s)).getStatusCode().value()).isEqualTo(403);assertThat(probes).isZero();
    }}
    @Test void unauthenticatedRequestDoesNotProbeStorage(){
        assertThat(controller.list("",new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);assertThat(probes).isZero();
    }
    @Test void storageFailureAndInputLabelHaveDistinctTranslationsInEveryLanguage(){
        try{for(var locale:List.of(Locale.KOREAN,Locale.ENGLISH,Locale.JAPANESE,Locale.SIMPLIFIED_CHINESE)){
            LocaleContextHolder.setLocale(locale);
            String error=UiMessages.text("problemQuestion.storageUnavailable","missing-translation");
            assertThat(error).doesNotContain("missing-translation").isNotEqualTo(UiMessages.text("problemQuestion.error","missing-label"));
            assertThat(UiMessages.text("problemQuestion.storageMissing","missing-translation")).contains("DBC_APP_RECORD").doesNotContain("RDF");
        }}finally{LocaleContextHolder.resetLocaleContext();}
    }
}
