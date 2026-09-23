package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.AiAssistant.*;
import com.dbcompanion.model.SelectAiTest.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SelectAiReviewTest {
    final Instant now=Instant.parse("2026-09-21T06:00:00Z");
    final Selection target=new Selection("APP","TEST");
    final Profile profile=new Profile(target,"oci","model","v1");
    final Profile reviewer=new Profile(new Selection("APP","REVIEW"),"oci","other","r1");
    Outcome outcome(Action action,Profile p,String q,String text){return new Outcome(action.name(),action,p,q,now,1,text,null,null,"complete");}
    Outcome snapshot(){return outcome(Action.PROMPT,profile,"한국어 question","metadata <script>");}
    SelectAiTest.State state(){var s=new SelectAiTest.State();s.select(target);s.finish(snapshot());return s;}
    SelectAiReview.Prepared prepare(SelectAiTest.State s){s.prepareReview();return s.review().prepare(s.reviewable("PROMPT"),s.latest(),reviewer,"ko",now);}
    @Test void sameQuestionUsesIdenticalSqlAndShowpromptInput(){
        assertThat(SelectAiTest.prompt(Action.SQL,"SELECT AI RUNSQL x","ko")).isEqualTo(SelectAiTest.prompt(Action.PROMPT,"SELECT AI RUNSQL x","ko")).doesNotStartWith("SELECT AI");
    }
    @Test void promptAndGenerationSnapshotsAreSeparateAndReviewNeverReplacesSql(){
        var s=state();var sql=outcome(Action.SQL,profile,"q","SELECT 1 FROM DUAL");s.finish(sql);s.finish(snapshot());
        assertThat(s.latest()).isSameAs(sql);assertThat(s.prompt()).isEqualTo(snapshot());
        prepare(s);s.beginReview();s.review().finish(new SelectAiReview.Result("PROMPT",reviewer,now,1,"advice",null,null));s.finishReview();
        assertThat(s.latest()).isSameAs(sql);assertThat(s.review().result().text()).isEqualTo("advice");
        s.finish(outcome(Action.CHAT,profile,"different","new"));assertThat(s.review().result()).isNull();
    }
    @Test void matchingRequiresExactQuestionFullProfileVersionAndSqlAction(){
        var p=snapshot();var sql=outcome(Action.SQL,profile,p.question(),"SQL");assertThat(SelectAiReview.matching(p,sql)).isSameAs(sql);
        assertThat(SelectAiReview.matching(p,outcome(Action.CHAT,profile,p.question(),"answer"))).isNull();
        assertThat(SelectAiReview.matching(p,outcome(Action.SQL,profile,"different","SQL"))).isNull();
        assertThat(SelectAiReview.matching(p,outcome(Action.SQL,new Profile(target,"oci","model","v2"),p.question(),"SQL"))).isNull();
        assertThat(SelectAiReview.matching(p,null)).isNull();
    }
    @Test void errorResponsesAreEvidenceNotExecutableSql(){
        String response="Sorry, unfortunately a valid SELECT statement could not be generated.\nWITH x AS (SELECT 1 FROM DUAL) SELECT * FROM x\nException encountered: ORA-20004: object_list";
        assertThat(SelectAiReview.sqlResponse(response)).isFalse();assertThat(SelectAiReview.responseCode(response)).isEqualTo("ORA-20004");
        var rejected=new Outcome("rejected",Action.SQL,profile,snapshot().question(),now,1,response,"rejected","ORA-20004","sql-response");
        var s=state();s.finish(rejected);assertThatThrownBy(()->s.executable("rejected")).isInstanceOf(Failure.class);
        assertThat(SelectAiReview.prompt(snapshot(),rejected,"ko")).contains("ORA-20004","generatedResponse","UNTRUSTED","not execution","NOT a captured historical request","Korean");
        for(String sql:List.of("SELECT 'ORA-20004' FROM DUAL","WITH x AS (SELECT 1 FROM DUAL) SELECT * FROM x","```sql\nSELECT 1 FROM DUAL\n```"))assertThat(SelectAiReview.sqlResponse(sql)).isTrue();
        for(String invalid:Arrays.asList(null,"","DELETE FROM x","SELECTED","WITHIN","```\nhello"))assertThat(SelectAiReview.sqlResponse(invalid)).isFalse();
    }
    @Test void evidenceIsEncodedNotClippedAndLargeInputRefused(){
        var p=outcome(Action.PROMPT,profile,"quote \" 日本語 中文 한국어","x".repeat(200_000));
        String result=SelectAiReview.prompt(p,null,"ja");assertThat(result).contains("Japanese","x".repeat(200_000),"quote \\\"");
        String payload=result.substring(result.indexOf("BEGIN UNTRUSTED JSON EVIDENCE\n")+"BEGIN UNTRUSTED JSON EVIDENCE\n".length(),result.lastIndexOf("\nEND UNTRUSTED JSON EVIDENCE"));
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(payload).has("generatedResponse")).isFalse();
        assertThatThrownBy(()->SelectAiReview.prompt(outcome(Action.PROMPT,profile,"q","x".repeat(480_000)),null,"ko")).isInstanceOf(Failure.class);
    }
    @Test void reviewRequiresConsentSingleUseOwnerReviewerAndExpiry(){
        for(String change:List.of("owner","reviewer","expiry","cancel","selection","refresh","newSql","newPrompt")){
            var s=state();var p=prepare(s);String token=p.preview().token();
            switch(change){case "cancel"->s.cancel(token);case "selection"->s.select(null);case "refresh"->s.profiles(true,List::of);case "newSql"->s.prepare("APP",profile,Action.SQL,"new","ko",now);case "newPrompt"->s.finish(snapshot());}
            assertThatThrownBy(()->s.review().consume(token,true,change.equals("owner")?"OTHER":"APP",change.equals("reviewer")?target:reviewer.selection(),change.equals("expiry")?now.plusSeconds(600):now)).isInstanceOf(Failure.class);
        }
        var s=state();var p=prepare(s);String token=p.preview().token();
        assertThatThrownBy(()->s.review().consume(token,false,"APP",reviewer.selection(),now)).isInstanceOf(Failure.class);
        assertThat(s.review().consume(token,true,"APP",reviewer.selection(),now)).isEqualTo(p);s.review().finish(null);
        assertThatThrownBy(()->s.review().consume(token,true,"APP",reviewer.selection(),now)).isInstanceOf(Failure.class);
    }
    @Test void oneConcurrentReviewAndSharedBusyBoundary() throws Exception {
        var s=state();var p=prepare(s);var count=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(8)){
            var tasks=new ArrayList<Future<?>>();for(int i=0;i<24;i++)tasks.add(executor.submit(()->{synchronized(s){try{s.idle();s.review().consume(p.preview().token(),true,"APP",reviewer.selection(),now);s.beginReview();count.incrementAndGet();}catch(Failure expected){}}}));for(var task:tasks)task.get();
        }
        assertThat(count).hasValue(1);
        assertThatThrownBy(()->s.prepare("APP",profile,Action.SQL,"q","ko",now)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->s.consume("none",true,"APP",now)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->s.select(target)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->s.prepareReview()).isInstanceOf(Failure.class);
        s.review().finish(null);s.finishReview();assertThat(prepare(s)).isNotNull();
    }
    @Test void promptErrorsAndOtherSelectionsCannotBeReviewed(){
        var s=state();s.select(new Selection("APP","OTHER"));assertThatThrownBy(()->s.reviewable("PROMPT")).isInstanceOf(Failure.class);
        s.select(target);s.finish(new Outcome("bad",Action.PROMPT,profile,"q",now,1,null,"error","ORA-12345","generate"));
        assertThatThrownBy(()->s.reviewable("bad")).isInstanceOf(Failure.class);
    }
}
