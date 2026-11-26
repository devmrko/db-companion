package com.dbcompanion;

import com.dbcompanion.service.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AiSetupToolLoopTest {
    final JsonMapper json=new JsonMapper();
    static String tool(String name){return "{\"type\":\"tool_call\",\"name\":\""+name+"\",\"arguments\":{}}";}
    static String finish(String action){return "{\"type\":\"final\",\"explanation\":\"Observed settings only\",\"action\":\""+action+"\"}";}
    @Test void actualServiceResultsArePassedBackBeforeAnActionIsProposed() {
        var prompts=new ArrayList<String>();var executed=new ArrayList<String>();
        var responses=new ArrayDeque<>(List.of(tool("profile.inspectOptions"),finish("enable-comments")));
        var result=AiSetupToolLoop.run("Reviewed scope",json,p->{prompts.add(p);return responses.remove();},name->{
            executed.add(name);return Map.of("comments","false","constraints","true");
        });
        assertThat(executed).containsExactly("profile.inspectOptions");
        assertThat(prompts).hasSize(2);assertThat(prompts.get(1)).contains("SERVER_TOOL_EXCHANGE","\"comments\":\"false\"");
        assertThat(result.advice().action()).isEqualTo("enable-comments");
        assertThat(result.executions()).singleElement().satisfies(e->{assertThat(e.sequence()).isEqualTo(1);assertThat(e.result()).contains("\"constraints\":\"true\"");});
        assertThatThrownBy(()->result.executions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void noToolOrInvalidOutputCannotProduceAnActionOrExecuteAService() {
        var calls=new AtomicInteger();
        for(String reply:List.of(finish("enable-comments"),"null","[]","{}",
                tool("execute-sql"),tool("ProfileEditService.save"),
                "{\"type\":\"tool_call\",\"name\":\"profile.inspectOptions\",\"arguments\":{\"profile\":\"OTHER\"}}",
                "{\"type\":\"tool_call\",\"name\":\"profile.inspectOptions\",\"arguments\":{},\"approved\":true}",
                "{\"type\":\"tool_call\",\"name\":\"execute-sql\",\"name\":\"profile.inspectOptions\",\"arguments\":{}}",
                tool("profile.inspectOptions")+" {}",
                "{\"type\":\"tool_call\",\"name\":\"profile.inspectOptions\",\"arguments\":[]}")) {
            assertThatThrownBy(()->AiSetupToolLoop.run("scope",json,p->reply,name->{calls.incrementAndGet();return "bad";}))
                    .as(reply).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls).hasValue(0);
    }
    @Test void repeatedReadsStopWithoutRepeatingTheServiceOrRetryingTheModel() {
        var models=new AtomicInteger();var services=new AtomicInteger();
        assertThatThrownBy(()->AiSetupToolLoop.run("scope",json,p->{models.incrementAndGet();return tool("setup.inspectHistory");},name->{services.incrementAndGet();return "READY";}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(models).hasValue(2);assertThat(services).hasValue(1);
    }
    @Test void threeDistinctReadsAndFinalAnswerFitTheFourCallBudget() {
        var responses=new ArrayDeque<>(AiSetupToolLoop.tools().stream().map(t->tool(t.name())).toList());responses.add(finish(""));
        var models=new AtomicInteger();
        var result=AiSetupToolLoop.run("scope",json,p->{models.incrementAndGet();return responses.remove();},name->Map.of("state","UNKNOWN"));
        assertThat(models).hasValue(4);assertThat(result.executions()).hasSize(3);assertThat(result.advice().action()).isEmpty();
    }
    @Test void failureOrOversizedServiceResultStopsBeforeAnotherModelCall() {
        for(boolean fail:List.of(true,false)) {
            var models=new AtomicInteger();
            assertThatThrownBy(()->AiSetupToolLoop.run("scope",json,p->{models.incrementAndGet();return tool("setup.inspectConfiguration");},name->{
                if(fail)throw new IllegalStateException("read failed");return "x".repeat(16001);
            })).isInstanceOf(RuntimeException.class);
            assertThat(models).hasValue(1);
        }
    }
    @Test void finalResponseCannotSmuggleAdditionalInstructionsOrUnregisteredChanges() {
        for(String last:List.of(finish("execute-sql"),
                "{\"type\":\"final\",\"explanation\":\"Review\",\"action\":\"\",\"approved\":true}",
                "{\"type\":\"final\",\"explanation\":\"Review\",\"action\":\"\",\"action\":\"enable-comments\"}")) {
            var responses=new ArrayDeque<>(List.of(tool("profile.inspectOptions"),last));
            assertThatThrownBy(()->AiSetupToolLoop.run("scope",json,p->responses.remove(),name->Map.of("comments","false")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void existingAdviceEnvelopeIsAcceptedOnlyAfterAnActualReadAndStillValidatesActions() {
        String legacy="{\"explanation\":\"Observed settings\",\"action\":\"enable-comments\"}";
        assertThatThrownBy(()->AiSetupToolLoop.run("scope",json,p->legacy,name->Map.of()))
                .isInstanceOf(AiSetupToolLoop.ProtocolException.class).hasMessage("READ_REQUIRED");
        var replies=new ArrayDeque<>(List.of(tool("profile.inspectOptions"),legacy));
        var outcome=AiSetupToolLoop.run("scope",json,p->replies.remove(),name->Map.of("comments","false"));
        assertThat(outcome.advice().action()).isEqualTo("enable-comments");
        assertThat(outcome.executions()).hasSize(1);
        for(String last:List.of("{\"explanation\":\"Read\",\"action\":\"execute-sql\"}",
                "{\"explanation\":\"Read\",\"action\":\"\",\"approved\":true}")) {
            var responses=new ArrayDeque<>(List.of(tool("profile.inspectOptions"),last));
            assertThatThrownBy(()->AiSetupToolLoop.run("scope",json,p->responses.remove(),name->Map.of()))
                    .isInstanceOf(AiSetupToolLoop.ProtocolException.class);
        }
    }
}
