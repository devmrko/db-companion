package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.SelectAiTest.*;
import com.dbcompanion.service.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SelectAiEvidenceTest {
    final OntologyInquiryTest fixtures=new OntologyInquiryTest();
    final JsonMapper json=new JsonMapper();
    final String question="사용자는 어떤 권역에 속하는가?";
    final Instant now=Instant.parse("2026-09-21T06:30:00Z");
    final AiAssistant.Profile profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"oci","m","v1");
    SelectAiEvidence.State state(){var s=new SelectAiEvidence.State();s.dataset("APP",false,()->fixtures.data(fixtures.entries()));return s;}
    SelectAiEvidence.Snapshot choose(SelectAiEvidence.State s){var search=s.search("APP",question,"");return s.choose(search.id(),search.routes().getFirst().id(),json);}
    SelectAiTest.Outcome outcome(Action action,SelectAiEvidence.Snapshot evidence){return new Outcome(action.name(),action,profile,question,now,10,"value",null,null,"complete",evidence);}
    @Test void onlyExplicitServerRouteCanBeResolvedForTheExactQuestion(){
        var s=state();assertThatThrownBy(()->s.resolve(true,"any",question)).isInstanceOf(AiAssistant.Failure.class);
        var selected=choose(s);assertThat(s.resolve(true,selected.hash(),question)).isSameAs(selected);
        assertThat(s.resolve(false,null,question)).isNull();
        for(var values:List.of(List.of("bad",question),List.of(selected.hash(),question+" ")))
            assertThatThrownBy(()->s.resolve(true,values.getFirst(),values.get(1))).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->s.resolve(false,selected.hash(),question)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->s.choose("unknown","P1",json)).isInstanceOf(AiAssistant.Failure.class);
        var search=s.search("APP",question,"");assertThatThrownBy(()->s.choose(search.id(),"P999",json)).isInstanceOf(Ontology.Failure.class);
    }
    @Test void payloadAndSerializedSnapshotExcludeDraftAndSensitiveDefinitions(){
        var value=choose(state());var tree=json.readTree(json.writeValueAsString(value));
        assertThat(value.source()).contains("업무 정의 권역","R1","paths","rdf").doesNotContain("업무 정의 사용자","EMAIL","secret description");
        assertThat(tree.has("entries")).isFalse();
        assertThat(tree.path("references").size()).isEqualTo(2);
        assertThat(value.hash()).isEqualTo(OntologyQueryService.hash(value.source()));
        assertThat(json.readTree(value.source()).path("paths").size()).isEqualTo(1);
    }
    @Test void cacheIsReusedUntilExplicitRefreshOrSchemaChange(){
        var s=new SelectAiEvidence.State();var count=new AtomicInteger();
        java.util.function.Supplier<OntologyInquiry.Dataset> load=()->{count.incrementAndGet();return fixtures.data(fixtures.entries());};
        s.dataset("APP",false,load);s.dataset("APP",false,load);assertThat(count).hasValue(1);
        var chosen=choose(s);s.dataset("APP",true,load);assertThat(count).hasValue(2);
        assertThatThrownBy(()->s.resolve(true,chosen.hash(),question)).isInstanceOf(AiAssistant.Failure.class);
        choose(s);s.dataset("OTHER",false,()->new OntologyInquiry.Dataset("OTHER",List.of(),OntologyRelations.analyze("DB","OTHER",List.of(),"now"),"now"));
        assertThat(s.selected()).isNull();assertThat(s.search("OTHER","question","").routes()).isEmpty();
    }
    @Test void schemaUnknownTableAndInvalidQuestionCannotBypassSearchBoundaries(){
        SelectAiEvidence.scope("APP",List.of("APP"));
        for(String schema:Arrays.asList(null,"OTHER","APP; SELECT"))assertThatThrownBy(()->SelectAiEvidence.scope(schema,List.of("APP"))).isInstanceOf(AiAssistant.Failure.class);
        var s=state();for(String q:Arrays.asList(null,"","a\0b","x".repeat(16001)))assertThatThrownBy(()->s.search("APP",q,"")).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->s.search("APP","\uD800","")).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->s.search("APP",question,"FORGED")).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->s.search("OTHER",question,"")).isInstanceOf(AiAssistant.Failure.class);
        assertThat(s.search("APP","x".repeat(16000),"A").question()).hasSize(16000);
    }
    @Test void currentDefinitionsMustMatchIdRevisionStateAndContent(){
        var value=choose(state());SelectAiEvidence.verify(value,fixtures.entries());
        var first=value.entries().getFirst();
        for(var changed:List.of(
                new Ontology.Entry(first.seq(),2,first.documentId(),first.state(),first.actor(),first.recordedAt(),first.document()),
                new Ontology.Entry(first.seq(),1,"different",first.state(),first.actor(),first.recordedAt(),first.document()),
                new Ontology.Entry(first.seq(),1,first.documentId(),"APPROVED",first.actor(),first.recordedAt(),first.document()),
                fixtures.entry("A","changed","DRAFT",first.document().source().keys()))){
            var entries=new ArrayList<>(value.entries());entries.set(0,changed);
            assertThatThrownBy(()->SelectAiEvidence.verify(value,entries)).isInstanceOf(AiAssistant.Failure.class);
        }
        assertThatThrownBy(()->SelectAiEvidence.verify(value,List.of())).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void enrichedSqlAndShowpromptAreIdenticalAndOffInputIsUnchanged(){
        var value=choose(state());
        String sql=SelectAiTest.prompt(Action.SQL,question,"ko",value);
        assertThat(sql).isEqualTo(SelectAiTest.prompt(Action.PROMPT,question,"ko",value))
                .contains(value.source(),"untrusted data","NOT actual records","Keep profile object-list restrictions","instead of guessing SQL").doesNotStartWith("SELECT AI");
        for(var action:Action.values())assertThat(SelectAiTest.prompt(action,question,"ko",null)).isEqualTo(SelectAiTest.prompt(action,question,"ko"));
        assertThat(SelectAiTest.prompt(Action.CHAT,question,"ja",value)).startsWith("Answer the user question in Japanese");
        assertThatThrownBy(()->SelectAiTest.prompt(Action.SQL,"changed","ko",value)).isInstanceOf(AiAssistant.Failure.class);
        var huge=new SelectAiEvidence.Snapshot(value.schema(),question,value.route(),value.references(),"large","x".repeat(64000),value.entries());
        assertThatThrownBy(()->SelectAiTest.prompt(Action.SQL,question,"ko",huge)).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void reviewNeverMixesDifferentEvidenceOrBareAndEnrichedSql(){
        var evidence=choose(state());var prompt=outcome(Action.PROMPT,evidence);var sql=outcome(Action.SQL,evidence);
        assertThat(SelectAiReview.matching(prompt,sql)).isSameAs(sql);
        assertThat(SelectAiReview.matching(prompt,outcome(Action.SQL,null))).isNull();
        assertThat(SelectAiReview.matching(outcome(Action.PROMPT,null),sql)).isNull();
        var other=new SelectAiEvidence.Snapshot(evidence.schema(),question,evidence.route(),evidence.references(),"different",evidence.source(),evidence.entries());
        assertThat(SelectAiReview.matching(prompt,outcome(Action.SQL,other))).isNull();
        String review=SelectAiReview.prompt(prompt,sql,"ko");
        assertThat(review).contains("ontologyHash",evidence.hash(),"evidence IDs and document revisions","generatedResponse").doesNotContain("secret description");
    }
    @Test void profileRefreshAndNewEvidenceInvalidateConsentWithoutTouchingInquiry(){
        var session=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP"));var s=session.aiTest();s.select(profile.selection());
        session.inquiry().dataset("APP",false,()->fixtures.data(fixtures.entries()));var original=fixtures.search();session.inquiry().remember(original);
        s.evidence().dataset("APP",false,()->fixtures.data(fixtures.entries()));var evidence=choose(s.evidence());
        var preview=s.prepare("APP",profile,Action.SQL,question,"ko",now,evidence);
        assertThat(preview.evidence()).isSameAs(evidence);s.invalidateRequests();
        assertThatThrownBy(()->s.consume(preview.preview().token(),true,"APP",now)).isInstanceOf(AiAssistant.Failure.class);
        s.select(profile.selection());assertThat(s.evidence().selected()).isNull();
        choose(s.evidence());s.profiles(true,List::of);assertThat(s.evidence().selected()).isNull();
        assertThat(session.inquiry().search(original.id(),"APP")).isSameAs(original);
    }
    @Test void outcomeAndExecutionKeepTheExactConsentSnapshot(){
        var s=new SelectAiTest.State();s.select(profile.selection());var evidence=choose(state());
        var prepared=s.prepare("APP",profile,Action.SQL,question,"ko",now,evidence);
        assertThat(s.consume(prepared.preview().token(),true,"APP",now).evidence()).isSameAs(evidence);
        var outcome=new Outcome("id",Action.SQL,profile,question,now,1,"SELECT 1 FROM DUAL",null,null,"complete",evidence);s.finish(outcome);
        var execution=s.prepareExecution("id",SelectAiReadSql.check(outcome.text()),now);
        assertThat(s.consumeExecution(execution.token(),true,now).evidence()).isSameAs(evidence);
    }
    @Test void serviceAndControllerUseReadOnlySavedMetadataNotAssistantOrProfileMutations() throws Exception {
        String service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/SelectAiTestService.java"));
        assertThat(service).contains("ontology.relationshipEntries","state.evidence().resolve(useOntology,evidenceHash,question)","verifyEvidence(session,prepared.evidence())","verifyEvidence(session,prepared.prompt().evidence())","verifyEvidence(session,value.evidence())")
                .doesNotContain("SET_ATTRIBUTE","queries.profileScope","ontology.install","ontology.snapshot","assistant().prepare","inquiry().");
        String controller=Files.readString(Path.of("src/main/java/com/dbcompanion/controller/SelectAiTestController.java"));
        assertThat(controller).contains("@PostMapping(\"/ai-test/evidence/search\")","@PostMapping(\"/ai-test/evidence/choose\")","com.dbcompanion.model.Ontology.Failure");
    }
}
