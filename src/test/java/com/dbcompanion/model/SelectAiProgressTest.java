package com.dbcompanion.model;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SelectAiProgressTest {
    @Test void monotonicLiveAndFinalTimesAreMeasuredNotEstimatedPercentages(){
        var clock=new AtomicLong();var state=new SelectAiProgress.State(clock::get);
        var trace=state.begin(UUID.randomUUID().toString(),"EXECUTE");trace.step(SelectAiProgress.Stage.CONNECTION);
        clock.set(25_000_000);trace.step(SelectAiProgress.Stage.QUERY);clock.set(145_000_000);
        var live=state.snapshots().getFirst();assertThat(live.elapsedMillis()).isEqualTo(145);
        assertThat(live.steps()).extracting(SelectAiProgress.Step::elapsedMillis).containsExactly(25L,120L);
        assertThat(live.status()).isEqualTo(SelectAiProgress.Status.RUNNING);
        trace.finish(false);clock.set(9_000_000_000L);trace.finish(true);trace.step(SelectAiProgress.Stage.FETCH);
        var failed=trace.snapshot();assertThat(failed.elapsedMillis()).isEqualTo(145);
        assertThat(failed.status()).isEqualTo(SelectAiProgress.Status.FAILED);
        assertThat(failed.steps().getLast().stage()).isEqualTo(SelectAiProgress.Stage.QUERY);
        assertThat(failed.steps().getLast().status()).isEqualTo(SelectAiProgress.Status.FAILED);
        assertThatThrownBy(()->failed.steps().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void historyIsBoundedSessionLocalAndContainsNoSqlOrQuestion(){
        var state=new SelectAiProgress.State();for(int i=0;i<12;i++)state.begin(UUID.randomUUID().toString(),"SQL").finish(true);
        assertThat(state.snapshots()).hasSize(8);assertThat(new SelectAiProgress.State().snapshots()).isEmpty();
        assertThat(SelectAiProgress.Snapshot.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("id","operation","startedAt","elapsedMillis","status","steps");
    }
    @Test void pollingDoesNotTakeTheLongRunningWorkflowMonitor() throws Exception {
        var state=new SelectAiTest.State();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var busy=executor.submit(()->{synchronized(state){entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}}});
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            try{assertThat(executor.submit(()->state.progress().snapshots()).get(500,TimeUnit.MILLISECONDS)).isEmpty();}
            finally{release.countDown();busy.get(1,TimeUnit.SECONDS);}
        }
    }
    @Test void clientCorrelationIsOptionalButBounded(){
        assertThat(SelectAiProgress.requestId(null)).hasSize(36);
        assertThatThrownBy(()->SelectAiProgress.requestId("sql or secret text")).isInstanceOf(IllegalArgumentException.class);
        String id=UUID.randomUUID().toString();assertThat(SelectAiProgress.requestId(id)).isEqualTo(id);
    }
}
