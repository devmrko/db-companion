package com.dbcompanion;

import static org.assertj.core.api.Assertions.*;
import com.dbcompanion.model.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelectAiBatchTest {
    private final Instant now=Instant.parse("2026-01-01T00:00:00Z");
    private List<SelectAiBatch.Item> items(){return List.of(new SelectAiBatch.Item("p1","q1","fp",null,null,null,null),new SelectAiBatch.Item("p2","q2","fp",null,null,null,null),new SelectAiBatch.Item("p3","q3","fp",null,null,null,null));}
    @Test void sequentialFailurePreservesAllResultsWithoutRetry(){var state=new SelectAiBatch.State();var plan=state.prepare(items(),now);var one=state.consumeNext(plan.generation(),plan.items().get(0).token(),true,now);state.finish(one.generation(),one.item().token(),"sql1",null);var two=state.consumeNext(plan.generation(),state.plan().items().get(1).token(),true,now);state.finish(two.generation(),two.item().token(),null,"ambiguous");var three=state.consumeNext(plan.generation(),state.plan().items().get(2).token(),true,now);state.finish(three.generation(),three.item().token(),"sql3",null);assertThat(state.plan().items()).extracting(SelectAiBatch.Item::status).containsExactly(SelectAiBatch.Status.SUCCEEDED,SelectAiBatch.Status.FAILED,SelectAiBatch.Status.SUCCEEDED);}
    @Test void consentExpiryReplayAndStaleGenerationCannotStartProviderWork(){var state=new SelectAiBatch.State();var plan=state.prepare(items(),now);assertThatThrownBy(()->state.consumeNext(plan.generation(),plan.items().getFirst().token(),false,now)).isInstanceOf(AiAssistant.Failure.class);assertThatThrownBy(()->state.consumeNext(plan.generation(),plan.items().getFirst().token(),true,now.plusSeconds(601))).isInstanceOf(AiAssistant.Failure.class);var start=state.consumeNext(plan.generation(),plan.items().getFirst().token(),true,now);assertThatThrownBy(()->state.consumeNext(plan.generation(),start.item().token(),true,now)).isInstanceOf(AiAssistant.Failure.class);assertThatThrownBy(()->state.finish("old",start.item().token(),"x",null)).isInstanceOf(AiAssistant.Failure.class);}
    @Test void cancelSkipsOnlyUnstartedAndprepareIsBlockedWhileRunning(){var state=new SelectAiBatch.State();var plan=state.prepare(items(),now);var start=state.consumeNext(plan.generation(),plan.items().getFirst().token(),true,now);assertThatThrownBy(()->state.prepare(items(),now)).isInstanceOf(AiAssistant.Failure.class);state.cancel(plan.generation());assertThat(state.plan().items()).extracting(SelectAiBatch.Item::status).containsExactly(SelectAiBatch.Status.RUNNING,SelectAiBatch.Status.SKIPPED,SelectAiBatch.Status.SKIPPED);state.finish(start.generation(),start.item().token(),"done",null);assertThat(state.plan().items().getFirst().result()).isEqualTo("done");}
}
