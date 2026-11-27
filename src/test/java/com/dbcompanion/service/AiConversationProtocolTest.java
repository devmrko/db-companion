package com.dbcompanion.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

class AiConversationProtocolTest {
    private final JsonMapper json=new JsonMapper();
    @Test void actualServiceResultIsReturnedToModelBeforeTheFinalAnswer(){
        var calls=new AtomicInteger();
        var result=AiConversationProtocol.run("question",json,Set.of("profiles.list"),prompt->{
            if(calls.getAndIncrement()==0)return "{\"type\":\"tool_call\",\"name\":\"profiles.list\",\"arguments\":{}}";
            assertTrue(prompt.contains("SERVER_TOOL_EXCHANGE"));assertTrue(prompt.contains("ACTUAL_PROFILE"));
            return "{\"type\":\"final\",\"answer\":\"추천입니다\",\"action\":\"clone\",\"arguments\":{\"profile\":\"ACTUAL_PROFILE\"}}";
        },(name,args)->List.of(Map.of("name","ACTUAL_PROFILE")));
        assertEquals(2,result.modelCalls());assertEquals(1,result.executions().size());assertEquals("clone",result.action());
    }
    @Test void adviceNeedsNoArtificialToolOrDraftMutation(){
        var result=AiConversationProtocol.run("name pending",json,Set.of(),prompt->"{\"type\":\"final\",\"answer\":\"AU_ANALYSIS를 추천합니다\",\"action\":\"\",\"arguments\":{}}",(name,args)->{fail("Unnecessary tool");return null;});
        assertEquals("",result.action());assertEquals(1,result.modelCalls());assertTrue(result.executions().isEmpty());
    }
    @Test void plainTextFinalCanOnlyDisplayAnAnswerAndWorkflowReturnsWithoutAnotherGeneration(){
        var plain=AiConversationProtocol.run("q",json,Set.of(),p->"모델과 인증을 먼저 확인하세요.",(n,a)->{fail("No tool requested");return null;});
        assertEquals("",plain.action());assertTrue(plain.executions().isEmpty());
        var calls=new AtomicInteger();var result=AiConversationProtocol.run("q",json,Set.of("creation.start"),p->{assertEquals(1,calls.incrementAndGet());return "{\"type\":\"tool_call\",\"name\":\"creation.start\",\"arguments\":{}}";},(n,a)->Map.of("prepared",true,"answer","초안을 엽니다."));
        assertEquals(1,result.modelCalls());assertEquals("초안을 엽니다.",result.answer());
    }
    @Test void literalLineBreaksInProviderTextArePreservedWithoutRelaxingActionOrKeyChecks(){
        var node=AiConversationProtocol.object("{\"type\":\"final\",\"answer\":\"first\nsecond\tline\",\"action\":\"\",\"arguments\":{}}",json);
        assertEquals("first\nsecond\tline",node.path("answer").asString());
        assertThrows(IllegalArgumentException.class,()->AiConversationProtocol.object("{\"type\":\"final\",\"answer\":\"one\ntwo\",\"type\":\"other\"}",json));
        assertDoesNotThrow(()->AiConversationProtocol.validateAction("suggest",json.readTree("{\"name\":\"AU_PROFILE\"}")));
        assertDoesNotThrow(()->AiConversationProtocol.validateAction("draft",json.readTree("{\"tables\":[\"ACTIVITY\"]}")));
    }
    @Test void arbitraryMethodsWritesSqlAndUnexpectedFieldsAreRejected(){
        for(String reply:List.of("{\"type\":\"tool_call\",\"name\":\"profile.delete\",\"arguments\":{}}",
                "{\"type\":\"final\",\"answer\":\"done\",\"action\":\"apply\",\"arguments\":{}}",
                "{\"type\":\"final\",\"answer\":\"done\",\"action\":\"draft\",\"arguments\":{\"sql\":\"DROP TABLE X\"}}",
                "{\"type\":\"final\",\"answer\":\"done\",\"action\":\"draft\",\"arguments\":{\"comments\":\"true\"}}"))
            assertThrows(IllegalArgumentException.class,()->AiConversationProtocol.run("q",json,Set.of(),p->reply,(n,a)->{fail("Unregistered call");return null;}));
        assertThrows(IllegalArgumentException.class,()->AiConversationProtocol.object("{\"type\":1,\"type\":2}",json));
        assertThrows(IllegalArgumentException.class,()->AiConversationProtocol.object("{} {}",json));
    }
    @Test void identicalToolCannotBeExecutedTwiceAndErrorsAreNotRetried(){
        var calls=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->AiConversationProtocol.run("q",json,Set.of("profiles.list"),
                p->"{\"type\":\"tool_call\",\"name\":\"profiles.list\",\"arguments\":{}}",(n,a)->{calls.incrementAndGet();return List.of();}));
        assertEquals(1,calls.get());
        var broken=new AtomicInteger();assertThrows(IllegalStateException.class,()->AiConversationProtocol.run("q",json,Set.of(),p->{broken.incrementAndGet();throw new IllegalStateException();},(n,a)->null));assertEquals(1,broken.get());
    }
    @Test void simulatedTrailingObservationIsDiscardedAndNeverUsedAsServiceEvidence(){
        var calls=new AtomicInteger();
        var result=AiConversationProtocol.run("q",json,Set.of("profiles.list"),prompt->{
            if(calls.getAndIncrement()==0)return "{\"type\":\"tool_call\",\"name\":\"profiles.list\",\"arguments\":{}}\n{\"fake_result\":\"FABRICATED_VALUE\"}";
            assertTrue(prompt.contains("REAL_VALUE"));assertFalse(prompt.contains("FABRICATED_VALUE"));
            return "{\"type\":\"final\",\"answer\":\"actual\",\"action\":\"\",\"arguments\":{}}";
        },(name,args)->List.of("REAL_VALUE"));
        assertEquals(2,result.modelCalls());assertEquals(1,result.executions().size());
        assertThrows(IllegalArgumentException.class,()->AiConversationProtocol.object("{\"type\":\"final\",\"answer\":\"a\",\"action\":\"\",\"arguments\":{}} {}",json));
        assertEquals("final",AiConversationProtocol.object("```json\n{\"type\":\"final\"}\n```",json).path("type").asString());
    }
    @Test void consentBoundsAndContextFieldsAreCheckedBeforeCallingTheModel(){
        assertThrows(IllegalArgumentException.class,()->AiConversationService.validateInput(new AiConversationService.Input("q","P","v",false,json.readTree("{}")),json));
        assertThrows(IllegalArgumentException.class,()->AiConversationService.validateInput(new AiConversationService.Input("q","P","v",true,json.readTree("{\"password\":\"not-accepted\"}")),json));
        assertThrows(IllegalArgumentException.class,()->AiConversationService.validateInput(new AiConversationService.Input("x".repeat(2001),"P","v",true,json.readTree("{}")),json));
        assertDoesNotThrow(()->AiConversationService.validateInput(new AiConversationService.Input("뭘로 넣는 게 좋아?","P","v",true,json.readTree("{\"creation\":{\"stage\":\"name\"}}")),json));
    }
}
