package com.dbcompanion;

import com.dbcompanion.common.db.QuestionAnalysisSql;
import com.dbcompanion.controller.QuestionAnalysisController;
import com.dbcompanion.model.*;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class QuestionLanguageTest {
    @Test void localeIsOnlyTheDefaultExplicitQuestionLanguageWins(){
        assertThat(QuestionAnalysisController.language("",Locale.ENGLISH)).isEqualTo(QuestionLanguage.EN);
        assertThat(QuestionAnalysisController.language(null,Locale.JAPANESE)).isEqualTo(QuestionLanguage.JA);
        assertThat(QuestionAnalysisController.language("",Locale.SIMPLIFIED_CHINESE)).isEqualTo(QuestionLanguage.ZH);
        assertThat(QuestionAnalysisController.language("ko",Locale.ENGLISH)).isEqualTo(QuestionLanguage.KO);
        assertThatThrownBy(()->QuestionAnalysisController.language("en';DROP",Locale.ENGLISH)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void setupForEachLanguageIsBoundedAndCopyOnly(){
        for(var language:QuestionLanguage.values()){
            assertThat(QuestionLanguage.of(language.code())).isEqualTo(language);
            assertThat(QuestionLanguage.forPolicy(language.policy())).isEqualTo(language);
            String sql=QuestionAnalysisSql.script(language);
            assertThat(sql).startsWith("DECLARE").endsWith("END;\n/\n");
            assertThat(sql).contains(language.preference(),language.policy(),language.lexer(),"EMPTY_STOPLIST","CTX_USER_PREFERENCES","CTX_USER_INDEXES");
            assertThat(sql).doesNotContain("DROP_","CREATE TABLE","CREATE INDEX","INSERT","UPDATE","GRANT");
        }
        assertThat(QuestionLanguage.forPolicy("DBC_BT_KO_POLICY")).isEqualTo(QuestionLanguage.KO);
        assertThatThrownBy(()->QuestionLanguage.forPolicy("DBC_BT_EN_POLICY")).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void configurationRequiresSessionAndDoesNotCache(){
        var response=new QuestionAnalysisController(null).configuration("ko",Locale.KOREAN,new MockHttpServletRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(401);assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }
}
