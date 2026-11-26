package com.dbcompanion;

import com.dbcompanion.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AiSetupAssistantTest {
    @Test void catalogueCoversEveryPurposeAndFailsClosedForUnobservedChecks() {
        for(var purpose:AiSetupCatalog.purposes()) {
            var expected=AiSetupCatalog.goal(purpose).requires();
            var actual=AiSetupCatalog.configuration(purpose,List.of());
            assertThat(actual).extracting(AiSetupService.Check::key).containsExactlyElementsOf(expected);
            assertThat(actual).allMatch(c->c.state()==AiSetupService.State.UNKNOWN);
        }
        assertThat(AiSetupCatalog.history(List.of())).hasSize(11).allMatch(c->c.state()==AiSetupService.State.UNKNOWN);
        assertThatThrownBy(()->AiSetupCatalog.goal("invented")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AiSetupCatalog.action("execute-sql")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void catalogueActionRequiresConfirmedPrerequisitesAndExistingEditor() {
        var action=AiSetupCatalog.action("enable-conversation");
        assertThat(action.attribute()).isEqualTo("conversation");assertThat(action.value()).isEqualTo("true");
        assertThat(action.apply()).isEqualTo("ProfileEditService.save");assertThat(action.confirmationRequired()).isTrue();
        var checks=List.of(new AiSetupService.Check("profile",AiSetupService.State.CONFIGURED,"/ai-profiles"));
        assertThat(AiSetupCatalog.eligible(action.id(),checks,List.of())).isFalse();
        assertThat(AiSetupCatalog.eligible(action.id(),checks,List.of(new AiSetupHistoryService.Check("profileStore",AiSetupService.State.UNKNOWN,"/ai-profiles","")))).isFalse();
        assertThat(AiSetupCatalog.eligible(action.id(),checks,List.of(new AiSetupHistoryService.Check("profileStore",AiSetupService.State.CONFIGURED,"/ai-profiles","")))).isTrue();
    }
    @Test void modelCannotSupplySqlOrArbitraryActionFields() {
        var mapper=new JsonMapper();
        assertThat(AiSetupAssistantService.parse("{\"explanation\":\"설정 안내\",\"enableConversation\":true}",mapper).enableConversation()).isTrue();
        for(String response:List.of("null","{}","{\"explanation\":\"x\",\"enableConversation\":\"true\"}",
                "{\"explanation\":\"x\",\"enableConversation\":true,\"sql\":\"DROP TABLE X\"}"))
            assertThatThrownBy(()->AiSetupAssistantService.parse(response,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void onlyFixedSingleActionsCanReachTheExistingEditor() {
        var mapper=new JsonMapper();
        for(String id:List.of("enable-conversation","enable-comments","enable-constraints")) {
            var parsed=AiSetupAssistantService.parse("{\"explanation\":\"Review first\",\"action\":\""+id+"\"}",mapper);
            assertThat(parsed.action()).isEqualTo(id);
            var action=AiSetupCatalog.action(id);
            assertThat(action.attribute()).isEqualTo(id.substring(7));
            assertThat(action.value()).isEqualTo("true");
            assertThat(action.confirmationRequired()).isTrue();
            assertThat(action.apply()).isEqualTo("ProfileEditService.save");
            assertThat(AiSetupCatalog.eligible(id,List.of(),List.of())).isFalse();
        }
        for(String action:List.of("set-credential","enable_custom_source_uri","execute-sql","enable-comments,enable-constraints"))
            assertThatThrownBy(()->AiSetupAssistantService.parse("{\"explanation\":\"x\",\"action\":\""+action+"\"}",mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AiSetupAssistantService.parse("{\"explanation\":\"No change\",\"action\":\"\"}",mapper).action()).isEmpty();
    }
    @Test void assistantReplyMustBeOneUnambiguousJsonObject() {
        var mapper=new JsonMapper();
        for(String response:List.of(
                "{\"explanation\":\"Review\",\"action\":\"enable-comments\"} {\"action\":\"enable-constraints\"}",
                "{\"explanation\":\"Review\",\"action\":\"enable-comments\",\"action\":\"enable-constraints\"}",
                "{\"explanation\":\"First\",\"explanation\":\"Second\",\"action\":\"enable-comments\"}"))
            assertThatThrownBy(()->AiSetupAssistantService.parse(response,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disabledOptionsAreAvailableChangesAndNotUnsupportedFeatures() {
        var checks=List.of(new AiSetupService.Check("profile",AiSetupService.State.CONFIGURED,"/ai-profiles"));
        var histories=List.of(new AiSetupHistoryService.Check("profileStore",AiSetupService.State.CONFIGURED,"/ai-profiles",""));
        var values=java.util.Map.of("constraints","false","comments","true");
        var candidates=AiSetupCatalog.candidates("nl2sql",values,checks,histories);
        assertThat(candidates).filteredOn(c->c.attribute().equals("constraints")).singleElement().satisfies(c->{
            assertThat(c.currentStoredValue()).isEqualTo("false");assertThat(c.changeAvailable()).isTrue();assertThat(c.prerequisitesReady()).isTrue();
        });
        assertThat(candidates).filteredOn(c->c.attribute().equals("comments")).singleElement().satisfies(c->assertThat(c.changeAvailable()).isFalse());
        assertThat(candidates).filteredOn(c->c.attribute().equals("conversation")).singleElement().satisfies(c->{
            assertThat(c.currentStoredValue()).isEqualTo("UNSET");assertThat(c.changeAvailable()).isTrue();
        });
        assertThat(AiSetupCatalog.candidates("nl2sql",values,checks,List.of())).noneMatch(AiSetupCatalog.Candidate::changeAvailable);
    }
}
