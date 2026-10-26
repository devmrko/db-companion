package com.dbcompanion;

import com.dbcompanion.common.exception.ObjectCreationException;
import com.dbcompanion.model.AiCreation;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ObjectCreationExceptionTest {
    @Test void databaseErrorsAreTypedWithoutRawMessagesOrCauses(){
        int[] codes={955,20022,1031,1013,20050,6550};String[] types={"DUPLICATE","DUPLICATE","ACCESS_DENIED","TIMEOUT","REFERENCE_INVALID","DATABASE_ERROR"};
        for(int i=0;i<codes.length;i++){
            var ex=ObjectCreationException.from(new SQLException("secret-password and raw bind values","42000",codes[i]),"CREATE",true);
            assertThat(ex).isInstanceOf(RuntimeException.class);assertThat(ex.getCause()).isNull();
            assertThat(ex.response()).containsEntry("code",types[i]).containsEntry("objectMayExist",true).containsEntry("automaticRetry",false).containsEntry("stage","CREATE");
            assertThat(ex.response().toString()).doesNotContain("secret-password","raw bind");
        }
    }
    @Test void preflightAndUncertainExecutionAreNotConflated(){
        var before=ObjectCreationException.from(new SQLException("denied","42000",1031),"PREFLIGHT",false);
        assertThat(before.status()).isEqualTo(403);assertThat(before.response()).containsEntry("objectMayExist",false);
        var timeout=ObjectCreationException.from(new SQLTimeoutException("private text"),"VERIFY",true);
        assertThat(timeout.status()).isEqualTo(504);assertThat(timeout.response()).containsEntry("code","TIMEOUT").containsEntry("objectMayExist",true);
        assertThat(ObjectCreationException.from(timeout,"VALIDATION",false)).isSameAs(timeout);
    }
    @Test void localDuplicateAndValidationPreserveSafeClassification(){
        assertThat(ObjectCreationException.from(AiCreation.error(409,"exists"),"PREFLIGHT",false).response()).containsEntry("code","DUPLICATE");
        assertThat(ObjectCreationException.from(new IllegalArgumentException("raw untrusted secret"),"VALIDATION",false).response().toString()).doesNotContain("raw untrusted secret");
    }
}
