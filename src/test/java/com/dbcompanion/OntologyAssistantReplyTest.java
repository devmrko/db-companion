package com.dbcompanion;
import com.dbcompanion.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class OntologyAssistantReplyTest {
    @Test void acceptsHarmlessEnvelopeButReportsMalformedFieldsWithoutEchoingModelText(){
        var search=new OntologyInquiry.Search("s","APP","metrics","",List.of(),List.of(),List.of(),"now");
        String output="{\"mode\":\"INDEPENDENT\",\"routeId\":null,\"tables\":[\"A\"],\"candidates\":[],\"reason\":\"separate\",\"questions\":[]}";
        assertThat(OntologyAssistantReply.plan("```json\n"+output+"\n```",search,Set.of("A"),Set.of(),json).routeId()).isEmpty();
        assertThatThrownBy(()->OntologyAssistantReply.plan(output.replace("\"questions\":[]","\"questions\":null"),search,Set.of("A"),Set.of(),json)).isInstanceOf(OntologyAssistantReply.InvalidReply.class).hasMessageContaining("questions");
        for(String value:List.of(output+" {}","sensitive prose",output.substring(0,40)))
            assertThatThrownBy(()->OntologyAssistantReply.plan(value,search,Set.of("A"),Set.of(),json)).isInstanceOf(OntologyAssistantReply.InvalidReply.class).hasMessageContaining("malformed JSON").hasMessageNotContaining("sensitive prose");
        assertThatThrownBy(()->OntologyAssistantReply.plan(output.replace("\"A\"","\"FORGED\""),search,Set.of("A"),Set.of(),json)).hasMessageContaining("unknown source");
    }
    @Test void independentPlanSelectsKnownSourcesAndOnlyReferenceCandidates(){
        var search=new OntologyInquiry.Search("s","APP","metrics","",List.of(),List.of(),List.of(),"now");
        String output="{\"mode\":\"INDEPENDENT\",\"routeId\":\"\",\"tables\":[\"A\",\"B\"],\"candidates\":[{\"id\":\"C1\",\"use\":\"EXCLUDE\",\"reason\":\"different populations\",\"selected\":false}],\"reason\":\"aggregate separately\",\"questions\":[]}";
        var plan=OntologyAssistantReply.plan(output,search,Set.of("A","B"),Set.of("C1"),json);
        assertThat(plan.tables()).containsExactly("A","B");assertThat(plan.candidates().getFirst().selected()).isFalse();
        for(String bad:List.of(output.replace("\"B\"","\"FORGED\""),output.replace("\"C1\"","\"FORGED\""),output.replace("false","true"),output.replace("EXCLUDE","JOIN"),output.replace("INDEPENDENT","PATH")))
            assertThatThrownBy(()->OntologyAssistantReply.plan(bad,search,Set.of("A","B"),Set.of("C1"),json)).isInstanceOf(RuntimeException.class);
    }
    final JsonMapper json=new JsonMapper();
    @Test void interpretationRequiresStructuredKnownReferences(){
        var value=OntologyAssistantReply.interpretation("{\"summary\":\"AU by country\",\"concepts\":[\"active users\"],\"questions\":[],\"termIds\":[\"T1\"]}",Set.of("T1"),json);
        assertThat(value.concepts()).containsExactly("active users");assertThat(value.questions()).isEmpty();
        assertThatThrownBy(()->OntologyAssistantReply.interpretation("{\"summary\":\"x\",\"concepts\":[],\"questions\":[],\"termIds\":[\"FORGED\"]}",Set.of("T1"),json)).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectsExecutableFieldsAndMalformedReplies(){
        for(String value:List.of("not json","[]","{\"sql\":\"DROP TABLE X\"}","{\"summary\":12,\"concepts\":[],\"questions\":[],\"termIds\":[]}"))assertThatThrownBy(()->OntologyAssistantReply.interpretation(value,Set.of(),json)).isInstanceOf(RuntimeException.class);
    }
    @Test void clarificationRemainsExplicit(){assertThat(OntologyAssistantReply.interpretation("{\"summary\":\"unclear\",\"concepts\":[],\"questions\":[\"Which date?\"],\"termIds\":[]}",Set.of(),json).questions()).containsExactly("Which date?");}
    @Test void recommendationCannotInventPathsOrEvidence(){
        var search=new OntologyInquiry.Search("S","APP","q","",List.of(),List.of(),List.of(),"now",List.of(),List.of(new OntologyInquiry.Route("P1",List.of("A"),List.of(),List.of("D1"),1)),List.of(),false);
        assertThat(OntologyAssistantReply.recommendation("{\"routeId\":\"P1\",\"reason\":\"match\",\"evidence\":[\"D1\"],\"questions\":[]}",search,json).routeId()).isEqualTo("P1");
        for(String value:List.of("{\"routeId\":\"FAKE\",\"reason\":\"x\",\"evidence\":[],\"questions\":[]}","{\"routeId\":\"P1\",\"reason\":\"x\",\"evidence\":[\"FAKE\"],\"questions\":[]}"))assertThatThrownBy(()->OntologyAssistantReply.recommendation(value,search,json)).isInstanceOf(RuntimeException.class);
    }
    @Test void invalidationDiscardsAssistantPlan(){var state=new OntologyInquiry.State();state.aiStep(new OntologyQueryService.AiStep("t","INTERPRET","APP","",com.dbcompanion.model.QuestionLanguage.KO,"",null,""));assertThat(state.aiStep("t")).isNotNull();state.invalidate();assertThatThrownBy(()->state.aiStep("t")).isInstanceOf(RuntimeException.class);}
}
