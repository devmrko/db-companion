package com.dbcompanion;

import com.dbcompanion.model.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ProblemEvaluationTest {
    static final String P="11111111-1111-4111-8111-111111111111", A="22222222-2222-4222-8222-222222222222";
    static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z");
    static final AiAssistant.Profile PROFILE=new AiAssistant.Profile(new AiAssistant.Selection("APP","REVIEW"),"test","test-model","v1");
    static ProblemQuestion.Detail detail(String response){
        var parent=new ProblemQuestion.Parent(1,P,"Period sales total","Wrong source suspected","Use transactions in the requested period","",ProblemQuestion.Status.RECEIVED,false,NOW,NOW);
        var attempt=new ProblemQuestion.Attempt(1,A,P,parent.question(),"",response,"SELECT total FROM snapshot","","APP.GENERATOR","test-model","","","","","MANUAL_ENTRY","USER_SUPPLIED",NOW,0,null,new ProblemQuestion.Snapshot("synthetic prompt","RECONSTRUCTED_SHOWPROMPT",NOW),null,null);
        return new ProblemQuestion.Detail(parent,List.of(attempt));
    }
    static ProblemEvaluation.Context context(){return ProblemEvaluation.context(detail("unverified response"),A,"No snapshot lifetime total",new JsonMapper());}
    @Test void criteriaHashIsStableAcrossAttemptsButChangesWithCriteria(){
        var json=new JsonMapper();var first=context();
        assertThat(ProblemEvaluation.context(detail("different result"),A,"No snapshot lifetime total",json).criteriaHash()).isEqualTo(first.criteriaHash());
        assertThat(ProblemEvaluation.context(detail("different result"),A,"Different required condition",json).criteriaHash()).isNotEqualTo(first.criteriaHash());
        assertThat(first.criteriaHash()).hasSize(64);
        assertThatThrownBy(()->ProblemEvaluation.context(detail("x"),P,"",json)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void promptRequiresEvidenceAndHumanDecisionNotReasoningTraceOrAutomaticGrading(){
        String prompt=ProblemEvaluation.prompt(context(),"ko");
        assertThat(prompt).contains("NOT automatic grading","Do not provide hidden chain-of-thought","Do not execute SQL","RECONSTRUCTED_SHOWPROMPT","Never infer totals","user supplied","UNTRUSTED".toLowerCase(),"The user makes the final decision");
    }
    @Test void stateRequiresConsentAndRejectsReuseExpiredTokensAndOtherOwners(){
        var state=new ProblemEvaluation.State();var p=state.prepare(context(),PROFILE,null,"ko","APP",NOW);
        assertThatThrownBy(()->state.consume(p.preview().token(),false,"APP",PROFILE.selection(),NOW)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(state.consume(p.preview().token(),true,"APP",PROFILE.selection(),NOW)).isEqualTo(p);
        assertThatThrownBy(()->state.consume(p.preview().token(),true,"APP",PROFILE.selection(),NOW)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->state.prepare(context(),PROFILE,null,"ko","APP",NOW)).isInstanceOf(AiAssistant.Failure.class);
        state.finish(null);String expired=state.prepare(context(),PROFILE,null,"ko","APP",NOW).preview().token();
        assertThatThrownBy(()->state.consume(expired,true,"APP",PROFILE.selection(),NOW.plusSeconds(600))).isInstanceOf(AiAssistant.Failure.class);
        String other=state.prepare(context(),PROFILE,null,"ko","APP",NOW).preview().token();
        assertThatThrownBy(()->state.consume(other,true,"OTHER",PROFILE.selection(),NOW)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void humanCanDisagreeWithAiButMustSupplyVerdictAndReason(){
        var report=new ProblemEvaluation.Report(A,context(),PROFILE,null,NOW,10,"AI suggests acceptable");
        var saved=ProblemEvaluation.decision(report,ProblemEvaluation.Verdict.NEEDS_CHANGE,"Period ignored","APP",NOW);
        assertThat(saved.report().text()).contains("acceptable");assertThat(saved.verdict()).isEqualTo(ProblemEvaluation.Verdict.NEEDS_CHANGE);
        assertThatThrownBy(()->ProblemEvaluation.decision(report,null,"reason","APP",NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ProblemEvaluation.decision(report,ProblemEvaluation.Verdict.UNDECIDED," ","APP",NOW)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(ProblemEvaluation.decision(report,ProblemEvaluation.Verdict.UNDECIDED,"password=SYNTHETIC_SECRET","APP",NOW).reason()).doesNotContain("SYNTHETIC_SECRET");
    }
    @Test void additionalCriteriaIsBoundedAndMaskedBeforeTransmission(){
        var json=new JsonMapper();assertThat(ProblemEvaluation.context(detail("x"),A,"password=SYNTHETIC_SECRET",json).input()).doesNotContain("SYNTHETIC_SECRET");
        assertThatThrownBy(()->ProblemEvaluation.context(detail("x"),A,"x".repeat(16001),json)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->ProblemEvaluation.context(detail("x".repeat(210000)),A,"",json)).isInstanceOf(AiAssistant.Failure.class);
    }
}
