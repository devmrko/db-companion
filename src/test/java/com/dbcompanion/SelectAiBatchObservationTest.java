package com.dbcompanion;

import com.dbcompanion.model.SelectAiBatch;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SelectAiBatchObservationTest {
    @Test void generatedAndFailedItemsPreserveTheirOwnObservationTimes(){
        var state=new SelectAiBatch.State();
        var now=Instant.parse("2026-01-01T00:00:00Z");
        var plan=state.prepare(List.of(item("one"),item("two"),item("three")),now);
        var first=state.consumeNext(plan.generation(),plan.items().get(0).token(),true,now.plusSeconds(2));
        state.finish(first.generation(),first.item().token(),"SELECT 1 FROM DUAL",null,now.plusMillis(3456));
        var second=state.consumeNext(plan.generation(),plan.items().get(1).token(),true,now.plusSeconds(5));
        state.finish(second.generation(),second.item().token(),null,"provider-error",now.plusMillis(5789));
        state.cancel(plan.generation());
        var result=state.plan().items();
        assertThat(result.get(0).requestedAt()).isEqualTo(now.plusSeconds(2));
        assertThat(result.get(0).elapsedMillis()).isEqualTo(1456);
        assertThat(result.get(1).requestedAt()).isEqualTo(now.plusSeconds(5));
        assertThat(result.get(1).elapsedMillis()).isEqualTo(789);
        assertThat(result.get(2).requestedAt()).isNull();
        assertThat(result.get(2).elapsedMillis()).isZero();
    }
    private static SelectAiBatch.Item item(String id){return new SelectAiBatch.Item(id,"question","profile",null,null,null,null);}
}
