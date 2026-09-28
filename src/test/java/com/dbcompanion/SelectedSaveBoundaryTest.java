package com.dbcompanion;

import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.SelectAiComparison;
import com.dbcompanion.model.SelectAiTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Synthetic state boundaries only; no persistence, provider, or database call. */
class SelectedSaveBoundaryTest {
    private final Instant now=Instant.parse("2026-01-01T00:00:00Z");
    private final AiAssistant.Profile a=new AiAssistant.Profile(new AiAssistant.Selection("APP","A"),"oci","model","v1");
    private final AiAssistant.Profile b=new AiAssistant.Profile(new AiAssistant.Selection("APP","B"),"oci","model","v1");
    private SelectAiTest.Outcome outcome(String id,AiAssistant.Profile profile){return new SelectAiTest.Outcome(id,SelectAiTest.Action.SQL,profile,"question",now,1,"SELECT 1 FROM DUAL",null,null,"complete");}
    private SelectAiComparison.Plan comparison(SelectAiComparison.State state,String id){
        var plan=state.prepare(a,b,"question","en",now);
        state.consume(plan.left().token(),true,now);state.finish(plan.left().token(),outcome(id,a));return plan;
    }
    @Test void concurrentSingleSaveBeginsExactlyOnce() throws Exception {
        var state=new SelectAiTest.State();state.finish(outcome("one",a));
        var preview=state.prepareProblemSave("one",now);var begins=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(6)){
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<24;i++)futures.add(executor.submit(()->{try{state.beginProblemSave("one",preview.token(),"destination",now);begins.incrementAndGet();}catch(AiAssistant.Failure expected){assertThat(expected.status()).isEqualTo(409);}}));
            for(var future:futures)future.get();
        }
        assertThat(begins).hasValue(1);state.finishProblemSave(preview.token(),"saved");
        assertThat(state.beginProblemSave("one",preview.token(),"destination",now).savedId()).isEqualTo("saved");
    }
    @Test void singleLedgerLimitDoesNotEraseAnUncertainResult(){
        var state=new SelectAiTest.State();state.finish(outcome("uncertain",a));
        var first=state.prepareProblemSave("uncertain",now);state.beginProblemSave("uncertain",first.token(),"destination",now);state.unconfirmedProblemSave(first.token());
        for(int i=1;i<64;i++){String id="result-"+i;state.finish(outcome(id,a));state.prepareProblemSave(id,now);}
        state.finish(outcome("overflow",a));assertThatThrownBy(()->state.prepareProblemSave("overflow",now)).isInstanceOf(AiAssistant.Failure.class);
        state.finish(outcome("uncertain",a));assertThatThrownBy(()->state.prepareProblemSave("uncertain",now)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void comparisonLedgerLimitDoesNotSilentlyEvictSaveHistory(){
        var state=new SelectAiComparison.State();
        for(int i=0;i<64;i++){String id="result-"+i;var plan=comparison(state,id);state.prepareSave(plan.generation(),"left",id,now);}
        var overflow=comparison(state,"overflow");
        assertThatThrownBy(()->state.prepareSave(overflow.generation(),"left","overflow",now)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void knownSinglePrewriteFailureAllowsAReviewedCorrectedDestination(){
        var state=new SelectAiTest.State();state.finish(outcome("one",a));var first=state.prepareProblemSave("one",now);
        state.beginProblemSave("one",first.token(),"parent-version-one",now);state.abortProblemSave(first.token());
        var reviewed=state.prepareProblemSave("one",now.plusSeconds(1));
        assertThatCode(()->state.beginProblemSave("one",reviewed.token(),"parent-version-two",now.plusSeconds(1))).doesNotThrowAnyException();
    }
    @Test void knownComparisonPrewriteFailureAllowsCorrectedSnapshotSelection(){
        var state=new SelectAiComparison.State();var plan=comparison(state,"one");var first=state.prepareSave(plan.generation(),"left","one",now);
        state.beginSave(plan.generation(),"left","one",first.token(),"snapshots-too-large",now);state.abortSave(first.token());
        var reviewed=state.prepareSave(plan.generation(),"left","one",now.plusSeconds(1));
        assertThatCode(()->state.beginSave(plan.generation(),"left","one",reviewed.token(),"snapshots-not-requested",now.plusSeconds(1))).doesNotThrowAnyException();
    }
}
