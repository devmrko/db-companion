package com.dbcompanion;

import com.dbcompanion.model.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class GlossaryComparisonTest {
    final Instant now=Instant.parse("2026-01-01T00:00:00Z");
    final AiAssistant.Profile profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"oci","model","v1");
    BusinessGlossary.Snapshot glossary(){
        var term=new BusinessGlossary.Term("00000000-0000-0000-0000-000000000001",7,"순매출",List.of(),"환불 제외","AMOUNT - REFUND",true,"v7");
        var hit=new BusinessGlossary.Hit(term,"TERM",List.of("순매출"));
        return BusinessGlossary.snapshot(new BusinessGlossary.Search("s","APP","P","순매출","EXACT_ALIAS",List.of(),List.of(),"",List.of(hit),false,now.plusSeconds(900)),List.of(hit),new JsonMapper(),now);
    }
    @Test void sameProfileOnlyChangesSelectedAttachmentAndKeepsReferenceSql(){
        var state=new SelectAiComparison.State();var snapshot=glossary();
        var plan=state.prepareGlossary(profile,"순매출","ko",snapshot,now);
        assertThat(plan.left().profile()).isEqualTo(plan.right().profile());
        assertThat(plan.left().source()).doesNotContain("AMOUNT - REFUND","definitions");
        assertThat(plan.right().source()).startsWith(plan.left().source()).contains("AMOUNT - REFUND","\"revision\":7");
        assertThat(plan.left().glossary()).isNull();assertThat(plan.right().glossary()).isEqualTo(snapshot);
        assertThat(plan.baseCalls()).isEqualTo(2);
        var prompts=state.prepareShowPrompt("ko",now);
        assertThat(prompts.left().source()).doesNotContain("AMOUNT - REFUND");
        assertThat(prompts.right().source()).contains("AMOUNT - REFUND");
        assertThat(prompts.right().glossary()).isEqualTo(snapshot);
    }
    @Test void foreignQuestionOrProfileDoesNotReuseGlossary(){
        var state=new SelectAiComparison.State();
        assertThatThrownBy(()->state.prepareGlossary(profile,"changed","ko",glossary(),now)).isInstanceOf(AiAssistant.Failure.class);
        var other=new AiAssistant.Profile(new AiAssistant.Selection("OTHER","P"),"oci","model","v1");
        assertThatThrownBy(()->state.prepareGlossary(other,"순매출","ko",glossary(),now)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void capturedDefinitionSurvivesResultAndSaveSelection(){
        var state=new SelectAiComparison.State();var snapshot=glossary();var plan=state.prepareGlossary(profile,"순매출","ko",snapshot,now);
        state.consume(plan.right().token(),true,now);
        var outcome=new SelectAiTest.Outcome("r",SelectAiTest.Action.SQL,profile,"순매출",now,1,"SELECT 1 FROM DUAL",null,null,"complete").withGlossary(snapshot);
        state.finish(plan.right().token(),outcome);
        var save=state.prepareSave(plan.generation(),"right","r",now);
        assertThat(state.beginSave(plan.generation(),"right","r",save.token(),"parent",now).selection().outcome().glossary()).isEqualTo(snapshot);
        assertThatThrownBy(()->state.consume(plan.right().token(),true,now)).isInstanceOf(AiAssistant.Failure.class);
    }
}
