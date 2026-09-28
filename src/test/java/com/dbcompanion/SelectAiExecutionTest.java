package com.dbcompanion;

import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.SelectAiTest;
import com.dbcompanion.service.SelectAiReadSql;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SelectAiExecutionTest {
    final Instant now=Instant.parse("2026-09-21T06:00:00Z");
    final AiAssistant.Selection selected=new AiAssistant.Selection("APP","P");
    final AiAssistant.Profile profile=new AiAssistant.Profile(selected,"oci","model","version");
    SelectAiTest.State state(){var state=new SelectAiTest.State();state.select(selected);state.finish(new SelectAiTest.Outcome("id",SelectAiTest.Action.SQL,profile,"q",now,10,"SELECT 1 FROM DUAL",null,null,"complete"));return state;}
    SelectAiTest.ExecutionPreview prepare(SelectAiTest.State state){return state.prepareExecution("id",SelectAiReadSql.check("SELECT 1 FROM DUAL"),now);}
    @Test void executionNeedsConsentAndTokenIsOneUseEvenAfterFailure(){
        var state=state();var preview=prepare(state);
        assertThatThrownBy(()->state.consumeExecution(preview.token(),false,now)).isInstanceOf(AiAssistant.Failure.class);
        var run=state.consumeExecution(preview.token(),true,now);assertThat(run.preview().sql()).isEqualTo("SELECT 1 FROM DUAL");assertThat(state.running()).isTrue();
        state.finishExecution(new SelectAiTest.ExecutionResult("id",null,"failed","ORA-00942",10));
        assertThat(state.latest().text()).isEqualTo("SELECT 1 FROM DUAL");assertThat(state.executionResult().code()).isEqualTo("ORA-00942");
        assertThatThrownBy(()->state.consumeExecution(preview.token(),true,now)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void expiredChangedCancelledOrRegeneratedRequestsCannotExecute(){
        for(String change:List.of("expired","selected","cancelled","regenerated","newpreview","refresh")){
            var state=state();var preview=prepare(state);
            switch(change){case "selected"->state.select(null);case "cancelled"->state.cancel(preview.token());case "regenerated"->state.prepare("APP",profile,SelectAiTest.Action.SQL,"new question","ko",now);case "newpreview"->prepare(state);case "refresh"->state.profiles(true,List::of);}
            assertThatThrownBy(()->state.consumeExecution(preview.token(),true,change.equals("expired")?now.plusSeconds(600):now)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(state.running()).isFalse();
        }
    }
    @Test void executionAndGenerationAreMutuallyExclusive() throws Exception {
        var state=state();var preview=prepare(state);var count=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(6)){
            var tasks=new ArrayList<Future<?>>();for(int i=0;i<24;i++)tasks.add(pool.submit(()->{try{state.consumeExecution(preview.token(),true,now);count.incrementAndGet();}catch(AiAssistant.Failure expected){}}));
            for(var task:tasks)task.get();
        }
        assertThat(count).hasValue(1);assertThatThrownBy(()->state.prepare("APP",profile,SelectAiTest.Action.CHAT,"q","ko",now)).isInstanceOf(AiAssistant.Failure.class);
        state.cancel(preview.token());assertThat(state.running()).isTrue();state.finishExecution(null);assertThat(state.running()).isFalse();
    }
    @Test void generationConsentCannotSurviveExecutionPreparation(){
        var state=state();var generation=state.prepare("APP",profile,SelectAiTest.Action.CHAT,"q","ko",now);prepare(state);
        assertThatThrownBy(()->state.consume(generation.preview().token(),true,"APP",now)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void referenceDisplayIsReusedAndUnavailableAnalysisDoesNotBlockExecution(){
        var state=state();assertThat(prepare(state).tables()).containsExactly("DUAL");
        var unknown=new com.dbcompanion.service.SelectAiSqlReferences.Analysis("UNSUPPORTED","RESPONSE",List.of());
        state.finish(new SelectAiTest.Outcome("id",SelectAiTest.Action.SQL,profile,"q",now,10,
                "SELECT 1 FROM DUAL",null,null,"complete",null,unknown,null));
        assertThat(prepare(state).tables()).isEmpty();
    }
    @Test void executionUsesStrictReadOnlyAndExistingBoundedResultReader() throws Exception {
        var source=Files.readString(Path.of("src/main/java/com/dbcompanion/service/SelectAiTestService.java"));
        assertThat(source).contains("strict.setEnforceReadOnly(true)","execute.setReadOnly(true)","ai.profile(value.profile().selection())").doesNotContain("executionRepository.verify");
        var repository=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/OntologyQueryRepository.java"));
        assertThat(repository).contains("setQueryTimeout(15)","setMaxRows(201)","rows.size()==200","getColumnCount()>40","str.length()>2000","size>1_000_000");
        var controller=Files.readString(Path.of("src/main/java/com/dbcompanion/controller/SelectAiTestController.java"));
        assertThat(controller).contains("record ExecutionPrepare(String resultId)","record Run(String token,boolean consent)").doesNotContain("record Execute(String sql");
        assertThat(Files.exists(Path.of("src/main/java/com/dbcompanion/repository/SelectAiExecutionRepository.java"))).isFalse();
    }
}
