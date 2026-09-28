package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.BusinessGlossary.*;
import com.dbcompanion.repository.QuestionAnalysisRepository;
import com.dbcompanion.service.*;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class QuestionAnalysisServiceTest {
    private static Connection connection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
        case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;
        default->m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
    });}
    final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){return connection();}};
    String policy=QuestionAnalysisSql.POLICY,receivedQuestion;
    int calls;
    boolean failure,preference,conflict;
    QuestionLanguage receivedLanguage;
    final QuestionAnalysisRepository repository=new QuestionAnalysisRepository(new JdbcTemplate(source),new JsonMapper()){
        @Override public String policy(String owner){assertThat(owner).isEqualTo("APP");if(conflict)throw BusinessGlossary.failure(409,"conflict");return policy;}
        @Override public String policy(String owner,QuestionLanguage language){receivedLanguage=language;return policy(owner);}
        @Override public boolean hasPreference(QuestionLanguage language){return preference;}
        @Override public List<Token> tokens(String name,String question){
            calls++;receivedQuestion=question;assertThat(name).isEqualTo(policy);
            if(failure)throw new IllegalStateException("native analysis failed");
            return List.of(new Token("사용자",1,3),new Token("권역",6,2));
        }
    };
    final QuestionAnalysisService service=new QuestionAnalysisService(source,repository);
    private PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));s.metadata().selectSchema("OTHER");return s;}
    @Test void nativeAnalysisFindsCommentOnlyEntitiesWithoutAnyDictionary(){try(var s=session()){
        String question="사용자와 권역은 어떻게 연결되나요?";
        var analysis=service.analyze(s,question);var fixtures=new OntologyPathsTest();var data=fixtures.data(fixtures.sample());
        assertThat(OntologyInquiry.search(data,question,"").routes()).isEmpty();
        var result=OntologyInquiry.search(data,question,"",analysis);
        assertThat(result.routes().getFirst().tables()).containsExactly("DEMO_APP_USER","DEMO_USER_ENTITLEMENT","DEMO_REGION");
        assertThat(receivedQuestion).isEqualTo(question);assertThat(calls).isEqualTo(1);assertThat(analysis.mode()).isEqualTo("ORACLE_TEXT");
    }}
    @Test void absentConfigurationIsExplicitAndTableNameSearchRemainsAvailable(){try(var s=session()){
        policy="";var analysis=service.analyze(s,"DEMO_APP_USER DEMO_REGION");assertThat(analysis.mode()).isEqualTo("ORACLE_TEXT_UNCONFIGURED");assertThat(calls).isZero();
        var fixtures=new OntologyPathsTest();var result=OntologyInquiry.search(fixtures.data(fixtures.sample()),"DEMO_APP_USER DEMO_REGION","",analysis);
        assertThat(result.routes().getFirst().tables()).containsExactly("DEMO_APP_USER","DEMO_USER_ENTITLEMENT","DEMO_REGION");
    }}
    @Test void legacyPolicyCompatibilityPreservesOriginalQuestionWithoutRequiringItsIndex(){try(var s=session()){
        policy="DBC_BT_KO_POLICY";String question="SHOW 사용자 권역 FOR AUGUST 2028-08-03";
        assertThat(service.analyze(s,question).mode()).isEqualTo("ORACLE_TEXT");assertThat(receivedQuestion).isEqualTo(question);
    }}
    @Test void nativeFailuresAreNotReportedAsNoEvidence(){try(var s=session()){
        failure=true;assertThatThrownBy(()->service.analyze(s,"question")).isInstanceOf(IllegalStateException.class).hasMessage("native analysis failed");
    }}
    @Test void explicitSetupContainsNoDictionaryOrBusinessDataChanges(){
        assertThat(QuestionAnalysisSql.setup()).contains("CREATE_PREFERENCE","CREATE_POLICY","KOREAN_MORPH_LEXER","CTXSYS.EMPTY_STOPLIST")
                .doesNotContain("CREATE TABLE","CREATE INDEX","DROP_","DBC_BUSINESS_TERM","DBC_BT_CTX","INSERT","UPDATE","GRANT");
        assertThat(QuestionAnalysisSql.tokens()).contains("policy_name=>?","document=>?","CTX_DOC.POLICY_TOKENS").doesNotContain("CREATE_","REGEXP","SPLIT");
    }
    @Test void missingPartialConflictAndReadyAreDistinctWithoutInstallation(){try(var s=session()){
        policy="";
        var missing=service.configuration(s,QuestionLanguage.EN);
        assertThat(missing.owner()).isEqualTo("APP");assertThat(missing.state()).isEqualTo("MISSING");
        assertThat(missing.setupSql()).contains("DBC_QA_EN_POLICY","BASIC_LEXER","RAISE_APPLICATION_ERROR").doesNotContain("KOREAN_MORPH_LEXER","DROP_","CREATE TABLE","CREATE INDEX");
        preference=true;var partial=service.configuration(s,QuestionLanguage.EN);assertThat(partial.state()).isEqualTo("PARTIAL");assertThat(partial.setupSql()).isEmpty();
        conflict=true;var invalid=service.configuration(s,QuestionLanguage.EN);assertThat(invalid.state()).isEqualTo("CONFLICT");assertThat(invalid.setupSql()).isEmpty();
        conflict=false;policy=QuestionLanguage.EN.policy();var ready=service.configuration(s,QuestionLanguage.EN);assertThat(ready.state()).isEqualTo("READY");assertThat(ready.setupSql()).isEmpty();assertThat(calls).isZero();
    }}
    @Test void explicitLanguageAndRawQuestionArePreservedInAnalysis(){try(var s=session()){
        policy=QuestionLanguage.EN.policy();String original="Show users in the August region";
        var result=service.analyze(s,original,QuestionLanguage.EN);
        assertThat(receivedLanguage).isEqualTo(QuestionLanguage.EN);assertThat(receivedQuestion).isEqualTo(original);
        assertThat(result.language()).isEqualTo("en");assertThat(result.lexer()).isEqualTo("BASIC_LEXER");assertThat(result.policy()).isEqualTo(policy);
        policy="";assertThat(service.analyze(s,original,QuestionLanguage.EN).mode()).isEqualTo("ORACLE_TEXT_UNCONFIGURED");assertThat(calls).isEqualTo(1);
    }}
}
