package com.dbcompanion;

import com.dbcompanion.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProblemRecoveryTest {
    final String before="00000000-0000-0000-0000-000000000001",after="00000000-0000-0000-0000-000000000002";
    ProblemQuestion.Attempt attempt(String id,String kind){return new ProblemQuestion.Attempt(1,id,"parent","question","","","","","","","","","","",kind,"CAPTURED",java.time.Instant.EPOCH,0,null,null,null,null);}
    @Test void recoveryLinksOnlyExistingDistinctNonNoteAttempts(){
        var attempts=List.of(attempt(before,"USER_SELECTED"),attempt(after,"USER_SELECTED"));
        var note=new ProblemQuestion.Recovery(before,after,"reason","changed definition","SQL generated; execution not verified");
        assertThat(ProblemQuestion.recovery(note,attempts)).isEqualTo(note);
        assertThatThrownBy(()->ProblemQuestion.recovery(note,List.of(attempt(before,"USER_SELECTED")))).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->ProblemQuestion.recovery(note,List.of(attempt(before,"MANUAL_RECOVERY"),attempt(after,"USER_SELECTED")))).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->ProblemQuestion.recovery(new ProblemQuestion.Recovery(before,before,"","action","result"),attempts)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void noRetestRemainsExplicitAndSecretsAreMasked(){
        var value=ProblemQuestion.recovery(new ProblemQuestion.Recovery(before,null,"","password=synthetic-test-value","not retested"),List.of(attempt(before,"USER_SELECTED")));
        assertThat(value.retestId()).isEmpty();assertThat(value.action()).contains("[REDACTED]").doesNotContain("synthetic-test-value");
        assertThatThrownBy(()->ProblemQuestion.recovery(new ProblemQuestion.Recovery(before,null,""," ","result"),List.of(attempt(before,"USER_SELECTED")))).isInstanceOf(AiAssistant.Failure.class);
    }
}
