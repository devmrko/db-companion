package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiSetupServiceToolsTest {
    static class Fixture {
        final OntologyReadCacheTest db=new OntologyReadCacheTest();
        final ArrayDeque<String> replies=new ArrayDeque<>();
        final List<String> prompts=new ArrayList<>();
        String version="v1",comments="false",sourceValue="[\"EXISTING\"]";int diagnoses,histories,edits,saves;boolean historyReady=true,sourceEnabled=true;
        Runnable afterModel=()->{};
        final DatabaseService database=new DatabaseService(null,db.source,null,null,null) {
            @Override public ProfilePage profiles(PoolSession s,String name){return new ProfilePage(List.of(new AiProfile("TARGET","ENABLED","","","","")),
                    List.of(new AiProfileAttribute("comments",comments),new AiProfileAttribute("constraints","false"),new AiProfileAttribute("conversation","false")));}
        };
        final AiAssistantRepository ai=new AiAssistantRepository(db.jdbc) {
            @Override public AiAssistant.Profile profile(AiAssistant.Selection selection){return new AiAssistant.Profile(selection,"test","synthetic",selection.name().equals("TARGET")?version:"control-v1");}
            @Override public String generate(String owner,String profile,String prompt,SelectAiTest.Action action){
                assertThat(profile).isEqualTo("CONTROL");prompts.add(prompt);String response=replies.remove();afterModel.run();return response;
            }
        };
        final ProfileHistoryRepository packages=new ProfileHistoryRepository(db.jdbc,db.json,null) {
            @Override public String packageOwner(String user){return "C##CLOUD$SERVICE";}
        };
        final AiSetupService setup=new AiSetupService(database,null,db.source,null) {
            @Override public List<Check> diagnose(PoolSession s,String profile,String purpose){diagnoses++;assertThat(profile).isEqualTo("TARGET");return List.of(new Check("profile",State.CONFIGURED,"/ai-profiles"));}
        };
        final AiSetupHistoryService history=new AiSetupHistoryService(null,null,null,null,db.source) {
            @Override public List<Check> diagnose(PoolSession s,List<AiProfileAttribute> attrs){histories++;return List.of(new Check("profileStore",historyReady?AiSetupService.State.CONFIGURED:AiSetupService.State.UNKNOWN,"/ai-profiles",""));}
        };
        final ProfileEditService editor=new ProfileEditService(db.source,packages,null,db.json) {
            @Override public ProfileEdit.Form edit(PoolSession s,ProfileEdit.Target target){edits++;return new ProfileEdit.Form(target,target.attribute().equals("semantic_source")?sourceValue:comments,version,2000,List.of(),List.of());}
            @Override public ProfileEdit.SaveResult save(PoolSession s,ProfileEdit.SaveRequest request){
                assertThat(request.schema()).isEqualTo("APP");assertThat(request.profile()).isEqualTo("TARGET");
                assertThat(request.attribute()).isIn("comments","semantic_source");assertThat(request.version()).isEqualTo(version);
                saves++;if(request.attribute().equals("semantic_source"))sourceValue=request.value();else comments=request.value();version="v2";return new ProfileEdit.SaveResult(true,"Verified by editor");
            }
        };
        final BusinessGlossaryService glossary=new BusinessGlossaryService(db.source,null,db.json){
            @Override public BusinessGlossary.Status status(PoolSession s){return new BusinessGlossary.Status("APP","READY","READY","Synthetic storage");}
            @Override public String historyStatus(PoolSession s){return "READY";}
        };
        final SemanticContextService semantic=new SemanticContextService(db.source,null,null){
            @Override public Status status(PoolSession s,String schema){assertThat(schema).isEqualTo("APP");return new Status("23.26.4.1.0",true,"","23.26.4.1.0",true,
                    List.of(new SemanticContext.Source("BUSINESS_TERMS","CUSTOM",sourceEnabled?"ENABLED":"DISABLED","Synthetic")));}
        };
        final AiSetupAssistantService service=new AiSetupAssistantService(db.source,ai,packages,database,setup,history,editor,db.json,glossary,semantic);
        PoolSession session(){var s=db.session();s.metadata().assistant().select(new AiAssistant.Selection("APP","CONTROL"));return s;}
        AiAssistant.Preview preview(PoolSession s,boolean tools){return service.preview(s,new AiSetupAssistantService.Input("nl2sql","TARGET","Enable comments",tools),Locale.KOREAN);}
    }
    static String sourcePreview(String source){return "{\"type\":\"tool_call\",\"name\":\"semantic.previewAttachment\",\"arguments\":{\"source\":\""+source+"\"}}";}
    static void attachmentReplies(Fixture f){f.replies.addAll(List.of(AiSetupToolLoopTest.tool("semantic.listSources"),sourcePreview("BUSINESS_TERMS"),AiSetupToolLoopTest.finish("attach-semantic-source")));}
    @Test void sourceAttachmentUsesActualServicesPreservesExistingAndRequiresSeparateConsent(){
        var f=new Fixture();attachmentReplies(f);
        try(var s=f.session()){
            var preview=f.preview(s,true);var result=f.service.analyze(s,preview.token(),true);
            assertThat(result.executions()).extracting(AiSetupToolLoop.Execution::name).containsExactly("semantic.listSources","semantic.previewAttachment");
            assertThat(f.prompts.getLast()).contains("EXISTING","BUSINESS_TERMS","confirmationRequired");assertThat(f.saves).isZero();
            var change=f.service.changePreview(s);assertThat(change.after()).isEqualTo("[\"EXISTING\",\"BUSINESS_TERMS\"]");
            assertThatThrownBy(()->f.service.apply(s,change.token(),false)).isInstanceOf(AiAssistant.Failure.class);assertThat(f.saves).isZero();
            assertThat(f.service.apply(s,change.token(),true).verified()).isTrue();assertThat(f.saves).isEqualTo(1);
            assertThat(f.sourceValue).isEqualTo(change.after());
            assertThatThrownBy(()->f.service.apply(s,change.token(),true)).isInstanceOf(AiAssistant.Failure.class);
        }
    }
    @Test void disabledMissingAndAlreadyAttachedSourcesCannotAuthorizeChanges(){
        for(String state:List.of("disabled","missing","already")){
            var f=new Fixture();if(state.equals("disabled"))f.sourceEnabled=false;if(state.equals("already"))f.sourceValue="[\"BUSINESS_TERMS\"]";
            f.replies.addAll(List.of(AiSetupToolLoopTest.tool("semantic.listSources"),sourcePreview(state.equals("missing")?"UNKNOWN":"BUSINESS_TERMS"),AiSetupToolLoopTest.finish("attach-semantic-source")));
            try(var s=f.session()){
                var preview=f.preview(s,true);assertThatThrownBy(()->f.service.analyze(s,preview.token(),true)).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(()->f.service.changePreview(s)).isInstanceOf(AiAssistant.Failure.class);assertThat(f.saves).isZero();
            }
        }
    }
    @Test void attachmentRechecksSourceAndProfileAtReviewAndApply(){
        for(boolean atApply:List.of(false,true)){
            var f=new Fixture();attachmentReplies(f);
            try(var s=f.session()){
                var preview=f.preview(s,true);f.service.analyze(s,preview.token(),true);
                if(atApply){var change=f.service.changePreview(s);f.sourceEnabled=false;
                    assertThatThrownBy(()->f.service.apply(s,change.token(),true)).isInstanceOf(AiAssistant.Failure.class);
                    f.sourceEnabled=true;assertThatThrownBy(()->f.service.apply(s,change.token(),true)).isInstanceOf(AiAssistant.Failure.class);
                }else{f.version="other";assertThatThrownBy(()->f.service.changePreview(s)).isInstanceOf(AiAssistant.Failure.class);}
                assertThat(f.saves).isZero();
            }
        }
    }
    @Test void glossaryReadReturnsActualServiceOutputThroughCallback(){
        var f=new Fixture();f.replies.addAll(List.of(AiSetupToolLoopTest.tool("glossary.inspectStorage"),AiSetupToolLoopTest.finish("")));
        try(var s=f.session()){var preview=f.preview(s,true);var result=f.service.analyze(s,preview.token(),true);
            assertThat(result.executions().getFirst().result()).contains("Synthetic storage","READY");assertThat(f.saves).isZero();}
    }
    @Test void failedNewPreviewInvalidatesAnEarlierChange(){
        var f=new Fixture();attachmentReplies(f);
        try(var s=f.session()){
            var preview=f.preview(s,true);f.service.analyze(s,preview.token(),true);var change=f.service.changePreview(s);
            assertThatThrownBy(()->f.service.preview(s,new AiSetupAssistantService.Input("invalid","TARGET","New request",true),Locale.KOREAN)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->f.service.apply(s,change.token(),true)).isInstanceOf(AiAssistant.Failure.class);assertThat(f.saves).isZero();
        }
    }
    @Test void anInvalidNewRequestDuringAnalysisInvalidatesOldResultsAndReleasesBusyState(){
        var f=new Fixture();f.replies.add(AiSetupToolLoopTest.tool("profile.inspectOptions"));
        try(var s=f.session()){
            var preview=f.preview(s,true);f.afterModel=()->assertThatThrownBy(()->f.service.preview(s,
                    new AiSetupAssistantService.Input("invalid","TARGET","New request",true),Locale.KOREAN)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->f.service.analyze(s,preview.token(),true)).isInstanceOf(AiAssistant.Failure.class);
            f.afterModel=()->{};f.replies.addAll(List.of(AiSetupToolLoopTest.tool("profile.inspectOptions"),AiSetupToolLoopTest.finish("")));
            var fresh=f.preview(s,true);assertThat(f.service.analyze(s,fresh.token(),true).action()).isEmpty();assertThat(f.saves).isZero();
        }
    }
    @Test void serviceModeActuallyDispatchesReadsAndStillRequiresSeparateChangeConsent() {
        var f=new Fixture();f.replies.addAll(List.of(AiSetupToolLoopTest.tool("profile.inspectOptions"),AiSetupToolLoopTest.finish("enable-comments")));
        try(var s=f.session()) {
            var preview=f.preview(s,true);assertThat(f.prompts).isEmpty();int before=f.diagnoses;
            assertThat(preview.source().substring(preview.source().lastIndexOf("REVIEWED_REQUEST")))
                    .doesNotContain("\"configuration\":", "\"history\":", "\"currentOptionValues\":");
            assertThatThrownBy(()->f.service.analyze(s,preview.token(),false)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(f.prompts).isEmpty();
            var result=f.service.analyze(s,preview.token(),true);
            assertThat(f.diagnoses).isGreaterThan(before);assertThat(f.prompts).hasSize(2);
            assertThat(result.executions()).extracting(AiSetupToolLoop.Execution::name).containsExactly("profile.inspectOptions");
            assertThat(f.edits).isZero();assertThat(f.saves).isZero();assertThat(f.comments).isEqualTo("false");
            var change=f.service.changePreview(s);assertThat(f.edits).isEqualTo(1);assertThat(f.saves).isZero();
            assertThatThrownBy(()->f.service.apply(s,change.token(),false)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(f.service.apply(s,change.token(),true).verified()).isTrue();assertThat(f.saves).isEqualTo(1);
            assertThatThrownBy(()->f.service.apply(s,change.token(),true)).isInstanceOf(AiAssistant.Failure.class);
            assertThatThrownBy(()->f.service.analyze(s,preview.token(),true)).isInstanceOf(AiAssistant.Failure.class);
        }
    }
    @Test void absentOptionUsesOneCallAndNeverInvokesTheToolLoop() {
        var f=new Fixture();f.replies.add("{\"explanation\":\"Read only\",\"action\":\"\"}");
        assertThat(new AiSetupAssistantService.Input("nl2sql","TARGET","Explain").serviceTools()).isFalse();
        assertThat(f.db.json.readValue("{\"purpose\":\"nl2sql\",\"profile\":\"TARGET\",\"question\":\"Explain\"}",AiSetupAssistantService.Input.class).serviceTools()).isFalse();
        try(var s=f.session()) {
            var preview=f.preview(s,false);int before=f.diagnoses;
            assertThat(preview.source()).doesNotContain("SERVICE TOOL MODE");
            var result=f.service.analyze(s,preview.token(),true);
            assertThat(f.prompts).hasSize(1);assertThat(f.diagnoses).isEqualTo(before);assertThat(result.executions()).isEmpty();
        }
    }
    @Test void configurationAndHistoryToolsUseExistingServices() {
        for(String name:List.of("setup.inspectConfiguration","setup.inspectHistory")) {
            var f=new Fixture();f.replies.addAll(List.of(AiSetupToolLoopTest.tool(name),AiSetupToolLoopTest.finish("")));
            try(var s=f.session()) {
                var preview=f.preview(s,true);int before=name.endsWith("History")?f.histories:f.diagnoses;
                var result=f.service.analyze(s,preview.token(),true);
                assertThat(name.endsWith("History")?f.histories:f.diagnoses).isEqualTo(before+1);
                assertThat(result.executions()).hasSize(1);assertThat(f.saves).isZero();
            }
        }
    }
    @Test void modelCannotBypassUnavailableActionsOrChangePrerequisites() {
        var f=new Fixture();f.historyReady=false;
        f.replies.addAll(List.of(AiSetupToolLoopTest.tool("profile.inspectOptions"),AiSetupToolLoopTest.finish("enable-comments")));
        try(var s=f.session()) {
            var preview=f.preview(s,true);
            assertThatThrownBy(()->f.service.analyze(s,preview.token(),true)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->f.service.changePreview(s)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(f.edits).isZero();assertThat(f.saves).isZero();
        }
    }
    @Test void scopeOrVersionChangesInvalidateTheRequestBeforeCallingTheModel() {
        for(boolean scope:List.of(true,false)) {
            var f=new Fixture();
            try(var s=f.session()) {
                var preview=f.preview(s,true);if(scope)s.metadata().selectSchema("OTHER");else f.version="changed";
                assertThatThrownBy(()->f.service.analyze(s,preview.token(),true)).isInstanceOf(AiAssistant.Failure.class);
                assertThat(f.prompts).isEmpty();assertThat(f.saves).isZero();
            }
        }
    }
    @Test void protocolFailureReportsOnlyCompletedReadsAndNeverKeepsAnApplicableProposal() {
        var f=new Fixture();f.replies.addAll(List.of(AiSetupToolLoopTest.tool("profile.inspectOptions"),AiSetupToolLoopTest.tool("profile.inspectOptions")));
        try(var s=f.session()) {
            var preview=f.preview(s,true);
            var request=new org.springframework.mock.web.MockHttpServletRequest();request.getSession().setAttribute(PoolSession.ATTRIBUTE,s);
            var response=new com.dbcompanion.controller.AiSetupAssistantController(f.service).analyze(
                    new com.dbcompanion.controller.AiSetupAssistantController.Run(preview.token(),true),request);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            String body=f.db.json.writeValueAsString(response.getBody());
            assertThat(body).contains("UNREGISTERED_OR_REPEATED_TOOL","profile.inspectOptions","\"modelCalls\":2")
                    .doesNotContain("CONTROL","control-v1","tool_call");
            assertThatThrownBy(()->f.service.changePreview(s)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(f.saves).isZero();
        }
    }
    @Test void aConcurrentProfileChangeStopsBeforeTheRequestedServiceRead() {
        var f=new Fixture();f.replies.add(AiSetupToolLoopTest.tool("setup.inspectConfiguration"));
        f.afterModel=()->f.version="external-change";
        try(var s=f.session()) {
            var preview=f.preview(s,true);int before=f.diagnoses;
            assertThatThrownBy(()->f.service.analyze(s,preview.token(),true)).isInstanceOf(AiAssistant.Failure.class);
            assertThat(f.diagnoses).isEqualTo(before);assertThat(f.saves).isZero();
        }
    }
}
