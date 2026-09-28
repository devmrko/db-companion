package com.dbcompanion;

import static org.assertj.core.api.Assertions.*;
import com.dbcompanion.model.*;
import org.junit.jupiter.api.Test;

class ProblemQuestionTest {
    @Test void parentRequiresExplicitBoundedTextAndKeepsDistinctQuestions(){
        var first=ProblemQuestion.create(new ProblemQuestion.Create("same","why","expected","",null));
        var second=ProblemQuestion.create(new ProblemQuestion.Create("same","other","expected","",ProblemQuestion.Status.ON_HOLD));
        assertThat(first.status()).isEqualTo(ProblemQuestion.Status.RECEIVED);assertThat(second.description()).isNotEqualTo(first.description());
        assertThatThrownBy(()->ProblemQuestion.create(new ProblemQuestion.Create("x\0","d","e","",null))).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void identifiersMustBeUuidAndOptionalSnapshotDoesNotAcceptNul(){
        assertThatThrownBy(()->ProblemQuestion.id("not-an-id")).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->ProblemQuestion.optional("x\0",2,"test")).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void everyPersistedDiagnosticFormMasksSyntheticSecrets(){
        String secret="SYNTHETIC_SECRET",begin="-----BEGIN "+"PRIVATE KEY-----",end="-----END "+"PRIVATE KEY-----";
        var inputs=java.util.List.of(begin+"\n"+secret+"\n"+end,"Authorization: Bearer "+secret,"Cookie: JSESSIONID="+secret,"{\"password\":\""+secret+"\"}","{\"api_key\":\""+secret+"\"}","ORACLE_PASSWORD='prefix "+secret+" with spaces'","ORACLE_WALLET_PATH=/private/"+secret);
        for(String input:inputs)assertThat(ProblemQuestion.optional(input,1000,"test")).doesNotContain(secret).contains("[REDACTED");
    }
    @Test void canonicalSnapshotPreservesValueAvailabilityAndLegacyFallback(){var captured=new ProblemQuestion.Snapshot("different","CAPTURED",java.time.Instant.EPOCH);var missing=new ProblemQuestion.Snapshot("","NOT_QUERIED",null);assertThat(com.dbcompanion.service.ProblemQuestionService.canonical(captured,"legacy").get("value")).isEqualTo("different");assertThat(com.dbcompanion.service.ProblemQuestionService.canonical(missing,"legacy")).containsEntry("availability","NOT_QUERIED");assertThat(com.dbcompanion.service.ProblemQuestionService.canonical(null,"legacy")).containsEntry("availability","LEGACY");}
}
