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
        String version="v1",comments="false";int diagnoses,histories,edits,saves;boolean historyReady=true;
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
            @Override public ProfileEdit.Form edit(PoolSession s,ProfileEdit.Target target){edits++;return new ProfileEdit.Form(target,comments,version,2000,List.of(),List.of());}
            @Override public ProfileEdit.SaveResult save(PoolSession s,ProfileEdit.SaveRequest request){
                assertThat(request.schema()).isEqualTo("APP");assertThat(request.profile()).isEqualTo("TARGET");
                assertThat(request.attribute()).isEqualTo("comments");assertThat(request.version()).isEqualTo(version);
                saves++;comments=request.value();version="v2";return new ProfileEdit.SaveResult(true,"Verified by editor");
            }
        };
        final AiSetupAssistantService service=new AiSetupAssistantService(db.source,ai,packages,database,setup,history,editor,db.json);
        PoolSession session(){var s=db.session();s.metadata().assistant().select(new AiAssistant.Selection("APP","CONTROL"));return s;}
        AiAssistant.Preview preview(PoolSession s,boolean tools){return service.preview(s,new AiSetupAssistantService.Input("nl2sql","TARGET","Enable comments",tools),Locale.KOREAN);}
    }
    @Test void serviceModeActuallyDispatchesReadsAndStillRequiresSeparateChangeConsent() {
        var f=new Fixture();f.replies.addAll(List.of(AiSetupToolLoopTest.tool("profile.inspectOptions"),AiSetupToolLoopTest.finish("enable-comments")));
        try(var s=f.session()) {
            var preview=f.preview(s,true);assertThat(f.prompts).isEmpty();int before=f.diagnoses;
            assertThat(preview.source()).doesNotContain("\"configuration\":", "\"history\":", "\"currentOptionValues\":");
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
