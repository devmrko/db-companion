package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.SelectAiInspection.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SelectAiInspectionTest {
    final Instant now=Instant.parse("2026-09-22T09:00:00Z");
    final AiAssistant.Profile profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"oci","model","v1");
    Snapshot snapshot(){return SelectAiInspection.create(profile,"질문",List.of(new AiProfileAttribute("object_list","[{\"owner\":\"APP\",\"name\":\"T\"},{\"owner\":\"Other\"}]"),new AiProfileAttribute("annotations","true")),now);}
    Table table(String name){return new Table("APP",name,"TABLE","테이블 <script>",List.of(new ColumnInfo(1,"GUID","VARCHAR2","N","식별자")),List.of(),"LOADED",null,now);}
    SelectAiTest.Outcome prompt(AiAssistant.Profile p,String question){return new SelectAiTest.Outcome("prompt",SelectAiTest.Action.PROMPT,p,question,now,1,"SHOWPROMPT",null,null,"complete");}
    @Test void explicitObjectsAndSchemaScopeStayCaseSensitive(){
        var s=snapshot();SelectAiInspection.requireObject(s,"APP","T");SelectAiInspection.requireObject(s,"Other","Whatever");
        for(String[] args:List.of(new String[]{"APP","OTHER"},new String[]{"other","T"},new String[]{"Other",""},new String[]{"APP","t"}))
            assertThatThrownBy(()->SelectAiInspection.requireObject(s,args[0],args[1])).isInstanceOf(AiAssistant.Failure.class);
        assertThat(SelectAiInspection.table(s,table("T")).tables()).hasSize(1);
        assertThatThrownBy(()->SelectAiInspection.table(s,table("SECRET"))).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void catalogIsOnlyAddedToReviewWhenFullProfileAndQuestionMatch(){
        var s=SelectAiInspection.table(snapshot(),table("T"));var p=prompt(profile,"질문");
        String review=SelectAiReview.prompt(p,null,"ko",s);
        assertThat(review).contains("currentCatalogInspection","GUID","식별자","v1","2026-09-22T09:00:00Z","partial current database lookup","not proof","object_list");
        assertThat(SelectAiReview.prompt(prompt(profile,"다른 질문"),null,"ko",s)).doesNotContain("\"currentCatalogInspection\":");
        var changed=new AiAssistant.Profile(profile.selection(),"oci","model","v2");
        assertThat(SelectAiReview.prompt(prompt(changed,"질문"),null,"ko",s)).doesNotContain("\"currentCatalogInspection\":");
        assertThat(SelectAiInspection.matches(null,p)).isFalse();
    }
    @Test void optionsAreFilteredAndMalformedObjectListsAreRejected(){
        var s=SelectAiInspection.create(profile,"q",List.of(new AiProfileAttribute("credential_name","secret"),new AiProfileAttribute("annotations","FALSE")),now);
        assertThat(s.settings()).doesNotContainKey("credential_name");assertThat(s.objects()).isEmpty();assertThat(SelectAiInspection.enabled(s,"annotations")).isFalse();
        assertThat(SelectAiInspection.enabled(snapshot(),"annotations")).isTrue();assertThat(SelectAiInspection.enabled(snapshot(),"comments")).isFalse();
        for(String value:List.of("{}","not json","[{}]","[{\"owner\":\"\"}]","[{\"owner\":\"APP\",\"name\":\"\"}]"))
            assertThatThrownBy(()->SelectAiInspection.create(profile,"q",List.of(new AiProfileAttribute("object_list",value)),now)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void detailRequiresVisibleFeedbackRowAndSearchClearsOldDetails(){
        var row=new AiFeedback.Item("AAABBBCCC000001abc","질문","negative","fix");
        var f=new Feedback("질문",1,new AiFeedback.Page(List.of(row),1,false),false,null,now);
        var detail=new FeedbackDetail(row.id(),new AiFeedback.Detail("질문","negative","response","fix",null,"SELECT ...","{}"),now);
        assertThatThrownBy(()->SelectAiInspection.detail(snapshot(),detail)).isInstanceOf(AiAssistant.Failure.class);
        var s=SelectAiInspection.detail(SelectAiInspection.feedback(snapshot(),f),detail);
        assertThat(s.feedbackDetails()).containsExactly(detail);
        assertThat(SelectAiInspection.detail(s,detail).feedbackDetails()).hasSize(1);
        assertThat(SelectAiInspection.feedback(s,new Feedback("other",2,new AiFeedback.Page(List.of(),2,false),false,null,now)).feedbackDetails()).isEmpty();
    }
    @Test void snapshotIsImmutableBoundedAndReplacesOnlyTheSameTable(){
        var old=snapshot();var next=SelectAiInspection.table(old,table("T"));assertThat(old.tables()).isEmpty();
        assertThat(SelectAiInspection.table(next,table("T")).tables()).hasSize(1);
        assertThatThrownBy(()->next.tables().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->next.settings().put("comments","false")).isInstanceOf(UnsupportedOperationException.class);
        var big=new Table("APP","T","TABLE","x".repeat(SelectAiInspection.MAX_CHARS),List.of(),List.of(),"LOADED",null,now);
        assertThatThrownBy(()->SelectAiInspection.table(old,big)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void selectedProfileRefreshAndOtherLoginInvalidateReferenceIds(){
        var state=new SelectAiTest.State();state.select(profile.selection());var s=snapshot();state.inspection(s);
        assertThat(state.inspection(s.id())).isEqualTo(s);
        assertThatThrownBy(()->state.inspection("other-id")).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->new SelectAiTest.State().inspection(s.id())).isInstanceOf(AiAssistant.Failure.class);
        state.profiles(true,List::of);assertThat(state.inspection()).isNull();
        state.inspection(s);state.select(null);assertThat(state.inspection()).isNull();
    }
    @Test void reviewPreparationContainsLoadedEvidenceButDoesNotAlterGeneration(){
        var state=new SelectAiTest.State();state.select(profile.selection());var p=prompt(profile,"질문");state.finish(p);
        state.inspection(SelectAiInspection.table(snapshot(),table("T")));
        var prepared=state.review().prepare(p,null,profile,"ko",now,state.inspection());
        assertThat(prepared.preview().source()).contains("currentCatalogInspection","테이블 <script>");
        state.inspection(snapshot());
        assertThatThrownBy(()->state.review().consume(prepared.preview().token(),true,"APP",profile.selection(),now)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(state.prompt()).isEqualTo(p);
    }
}
