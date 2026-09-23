package com.dbcompanion;

import com.dbcompanion.common.db.FeedbackTrackingSql;
import com.dbcompanion.common.db.FeedbackTrackingSql.Target;
import com.dbcompanion.repository.FeedbackTrackingRepository;
import com.dbcompanion.repository.FeedbackTrackingRepository.Check;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FeedbackTrackingTest {
    private final Target target=new Target("DEMO_APP","P","303",123);
    @Test void identitiesAreBoundedAndTriggerNameChangesWithRecreatedObjects() {
        assertThat(FeedbackTrackingSql.triggerName(target)).matches("DBC_FH_[A-F0-9]{22}");
        for(var other:List.of(new Target("OTHER","P","303",123),new Target("DEMO_APP","Q","303",123),new Target("DEMO_APP","P","304",123),new Target("DEMO_APP","P","303",124)))
            assertThat(FeedbackTrackingSql.triggerName(target)).isNotEqualTo(FeedbackTrackingSql.triggerName(other));
        assertThatThrownBy(()->new Target("S","P","1; DROP",2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Target("S","P","1",0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Target("","P","1",2)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rowTriggerKeepsWholeJsonActorTimeAndDoesNotSwallowErrors() {
        String sql=FeedbackTrackingSql.create(target);
        assertThat(sql).contains("AFTER INSERT OR UPDATE OR DELETE ON \"DEMO_APP\".\"P_FEEDBACK_VECINDEX$VECTAB\"",
                "FOR EACH ROW\nDISABLE\nDECLARE","'ROW_CHANGE'","'FEEDBACK'","'P','303'","v_operation", "SYS_CONTEXT('USERENV','SESSION_USER')",
                "SYS_EXTRACT_UTC(SYSTIMESTAMP)","JSON_SERIALIZE(:OLD.ATTRIBUTES RETURNING CLOB)","JSON_SERIALIZE(:NEW.ATTRIBUTES RETURNING CLOB)",
                "'content' VALUE :OLD.CONTENT","'content' VALUE :NEW.CONTENT","FORMAT JSON RETURNING CLOB","\"DBC_AI_HISTORY\"")
                .doesNotContain("OR REPLACE","COMMIT","AUTONOMOUS_TRANSACTION","EXCEPTION","SUBSTR","EMBEDDING","DBMS_CLOUD_AI");
        String quoted=FeedbackTrackingSql.create(new Target("A\"B","O'P","1",2));
        assertThat(quoted).contains("\"A\"\"B\"","'O''P'");
    }
    @Test void sourceComparisonOnlyToleratesDictionaryHeaderAndEnableFlag() {
        String sql=FeedbackTrackingSql.create(target);
        String actual=sql.substring("CREATE ".length()).replace("\nDISABLE\n", "\n")+"\n";
        assertThat(FeedbackTrackingSql.sourceMatches(target,actual)).isTrue();
        assertThat(FeedbackTrackingSql.sourceMatches(target,actual.replace("'303'","'304'"))).isFalse();
        assertThat(FeedbackTrackingSql.sourceMatches(target,actual.replace("v_old,v_new", "v_new,v_old"))).isFalse();
        assertThat(FeedbackTrackingSql.sourceMatches(target,null)).isFalse();
    }
    private List<Check> checks(boolean upgraded) {
        return FeedbackTrackingSql.expansions().stream().map(e->new Check(upgraded?e.name():"SYS_"+e.name(),upgraded?e.newExpression():e.oldExpression(),"ENABLED","VALIDATED")).toList();
    }
    @Test void upgradeAcceptsExactLegacyCurrentAndPartialButRejectsUnknownOrDisabledRules() {
        FeedbackTrackingRepository.validateChecks(checks(false));FeedbackTrackingRepository.validateChecks(checks(true));
        var partial=new ArrayList<>(checks(false));partial.addAll(checks(true));FeedbackTrackingRepository.validateChecks(partial);
        var bad=new ArrayList<>(checks(false));bad.set(0,new Check("X","OBJECT_TYPE IN ('PROFILE')","ENABLED","VALIDATED"));
        assertThatThrownBy(()->FeedbackTrackingRepository.validateChecks(bad)).hasMessageContaining("Feedback tracking conflict");
        bad.set(0,new Check("X",checks(false).getFirst().expression(),"DISABLED","NOT VALIDATED"));
        assertThatThrownBy(()->FeedbackTrackingRepository.validateChecks(bad)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->FeedbackTrackingRepository.validateChecks(List.of())).isInstanceOf(RuntimeException.class);
    }
    @Test void checkNormalizationPreservesLiteralMeaningAndGrouping() {
        assertThat(FeedbackTrackingSql.condition(" (( \"OBJECT_TYPE\" IN ('PROFILE','FEEDBACK') )) "))
                .isEqualTo(FeedbackTrackingSql.condition("OBJECT_TYPE IN ('PROFILE','FEEDBACK')"));
        assertThat(FeedbackTrackingSql.condition("OBJECT_TYPE='FEED BACK'"))
                .isNotEqualTo(FeedbackTrackingSql.condition("OBJECT_TYPE='FEEDBACK'"));
        assertThat(FeedbackTrackingSql.condition("(A AND B) OR (C AND D)")).isEqualTo("(AANDB)OR(CANDD)");
    }
    @Test void preparationPreservesRowsAndAddsConstraintsBeforeRemovingKnownOldOnes() throws Exception {
        String code=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/FeedbackTrackingRepository.java"));
        assertThat(code.indexOf(" ENABLE VALIDATE")).isLessThan(code.indexOf(" DROP CONSTRAINT "));
        assertThat(code).contains("validateChecks(initialChecks)","same(c.expression(),e.oldExpression())","same(c.expression(),e.newExpression())",
                "OBJECT_TYPE='FEEDBACK' AND OBJECT_NAME=?","ENTRY_KIND='ROW_CHANGE'","FETCH NEXT 11 ROWS ONLY","SUBSTR(TRIGGER_NAME,1,7)='DBC_FH_'")
                .doesNotContain("DELETE FROM","TRUNCATE TABLE","DROP TABLE","OR REPLACE","FEEDBACK(");
    }
    @Test void serviceEnforcesOwnerStaleIdentityAndDoesNotGrantOrUseCachedPermission() throws Exception {
        String code=Files.readString(Path.of("src/main/java/com/dbcompanion/service/FeedbackTrackingService.java"));
        assertThat(code).contains("changing&&!meta.info().username().equals(schema)","before.tableId(),request.tableId()",
                "before.profileId(),request.profileId()","source.clear()","repository.requireCompatible(target,enable)","repository.archiveState(target.schema())")
                .doesNotContain("GRANT ","historyState(");
    }
}
