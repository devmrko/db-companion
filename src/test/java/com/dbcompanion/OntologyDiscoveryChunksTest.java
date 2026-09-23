package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyRelations.*;
import com.dbcompanion.service.*;
import com.dbcompanion.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyDiscoveryChunksTest {
    final JsonMapper json=new JsonMapper();final OntologyPipelineTest f=new OntologyPipelineTest();final Instant now=Instant.now();
    final OntologyDiscovery.Batch batch=new OntologyDiscovery.Batch(List.of("ORDERS","CUSTOMER"),"{\"privatePayload\":true}");
    OntologyPipeline.State state(int calls){var state=new OntologyPipeline.State();state.prepare(new OntologyDiscovery.Plan("t","APP",f.profile,Collections.nCopies(calls,batch),2,now.plusSeconds(60)),List.of());return state;}
    Relation row(String id){return new Relation(id,"ORDERS","APP","CUSTOMER",List.of("BUYER_NO"),List.of("CUSTOMER_ID"),"CANDIDATE","AI","r","",List.of("AI_RDF"),null,null);}
    @Test void fiftyFiveCallsNeedFiveExplicitAuthorizationsAndNoDuplicateResults(){
        var state=state(55);var windows=new ArrayList<Integer>();
        while(!state.progress().done()){
            int start=state.progress().nextIndex();assertThatThrownBy(()->state.begin("t",start,true,"APP",now)).isInstanceOf(Failure.class);
            state.authorize("t",start,true,"APP",now);int end=state.progress().authorizedUntil();windows.add(end-start);
            for(int i=start;i<end;i++){state.begin("t",i,true,"APP",now);state.finish(List.of(row("same")),true);}
        }
        assertThat(windows).containsExactly(12,12,12,12,7);assertThat(state.progress().completed()).isEqualTo(55);assertThat(state.progress().candidates()).isEqualTo(1);assertThat(state.progress().failed()).isEmpty();
    }
    @Test void stoppingAnInflightCallRetainsItsResultAndResumesAtTheNextIndex(){
        var state=state(55);state.authorize("t",0,true,"APP",now);state.begin("t",0,true,"APP",now);state.stop("t");
        assertThat(state.progress().running()).isTrue();assertThatThrownBy(()->state.authorize("t",1,true,"APP",now)).isInstanceOf(Failure.class);
        state.finish(List.of(row("r")),true);assertThat(state.progress().completed()).isEqualTo(1);assertThat(state.progress().paused()).isTrue();
        assertThatThrownBy(()->state.begin("t",1,true,"APP",now)).isInstanceOf(Failure.class);
        state.authorize("t",1,true,"APP",now);state.begin("t",1,true,"APP",now);state.finish(List.of(),true);
        assertThat(state.progress().completed()).isEqualTo(2);assertThat(state.progress().candidates()).isEqualTo(1);
    }
    @Test void unknownCallConsumesIndexButIsNeverReportedAsSuccessfulOrRetried(){
        var state=state(2);state.authorize("t",0,true,"APP",now);state.begin("t",0,true,"APP",now);state.finish(List.of(),false);state.finish(List.of(),false);
        assertThat(state.progress().completed()).isZero();assertThat(state.progress().failed()).containsExactly(1);assertThat(state.progress().nextIndex()).isEqualTo(1);
        assertThatThrownBy(()->state.authorize("t",0,true,"APP",now)).isInstanceOf(Failure.class);
        state.authorize("t",1,true,"APP",now);state.begin("t",1,true,"APP",now);state.finish(List.of(),true);
        assertThat(state.progress().done()).isTrue();assertThat(state.progress().completed()).isEqualTo(1);assertThat(state.progress().failed()).containsExactly(1);
    }
    @Test void expiredWindowNeedsFreshConsentAndCannotReuseAnOldOrForeignToken(){
        var state=state(55);state.authorize("t",0,true,"APP",now);
        assertThatThrownBy(()->state.begin("t",0,true,"APP",now.plusSeconds(1201))).isInstanceOf(Failure.class);
        assertThatThrownBy(()->state.authorize("t",0,false,"APP",now)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->state.authorize("wrong",0,true,"APP",now)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->state.authorize("t",0,true,"OTHER",now)).isInstanceOf(Failure.class);
        state.authorize("t",0,true,"APP",now.plusSeconds(3600));state.begin("t",0,true,"APP",now.plusSeconds(3600));state.finish(List.of(),true);
        assertThat(state.progress().completed()).isEqualTo(1);
    }
    @Test void candidateSafetyLimitRetainsEntireFinalResponseAndBlocksMoreCalls(){
        var state=state(500);int i=0;
        while(!state.progress().resultLimit()){
            if(state.progress().paused())state.authorize("t",i,true,"APP",now);state.begin("t",i,true,"APP",now);
            int index=i;state.finish(IntStream.range(0,99).mapToObj(n->row(index+"-"+n)).toList(),true);i++;
        }
        assertThat(state.progress().candidates()).isEqualTo(10098);assertThat(state.progress().completed()).isEqualTo(102);
        int next=i;assertThatThrownBy(()->state.authorize("t",next,true,"APP",now)).isInstanceOf(Failure.class);
    }
    @Test void publicSummaryNeverSerializesEveryCallPayload(){
        var state=state(124750);String value=json.writeValueAsString(state.preview());
        assertThat(value).doesNotContain("privatePayload","batches","sourceColumns");assertThat(value.length()).isLessThan(1500);
        assertThat(json.readTree(value).path("progress").path("total").asInt()).isEqualTo(124750);
    }
    @Test void fiveHundredBlocksProduceLazyBoundedCallsNotQuadraticPayloadCopies(){
        var sf=new OntologyScopeTest();var entries=IntStream.range(0,500).mapToObj(i->sf.longEntry("T"+i,16000)).toList();
        var estimate=OntologyDiscovery.estimate(entries,json);assertThat(estimate.budget().allowed()).isTrue();assertThat(estimate.batches()).hasSize(124750);
        assertThat(estimate.batches().getFirst().tables()).containsExactly("T0","T1");assertThat(estimate.batches().getLast().tables()).containsExactly("T498","T499");
        assertThat(estimate.budget().maxBatchCharacters()).isLessThanOrEqualTo(AiAssistant.MAX_SOURCE);assertThat(estimate.budget().transmissionCharacters()).isGreaterThan(Integer.MAX_VALUE);
        var plan=new OntologyDiscovery.Plan("t","APP",f.profile,estimate.batches(),500,now.plusSeconds(60),estimate.budget());
        assertThat(plan.batches()).isSameAs(estimate.batches());assertThatThrownBy(()->plan.batches().set(0,batch)).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void resumeAndPayloadAreReadOnlyAndProfileChangesAreRejectedBeforeAi(){
        var fixture=new OntologyScopeServiceTest();try(var s=fixture.session()){
            s.metadata().assistant().select(f.profile.selection());var service=new OntologyPipelineService(fixture.source,fixture.repository,fixture.ai,null,null,json);
            var preview=service.preview(s,"APP",List.of("ORDERS","CUSTOMER"));String token=preview.token();
            assertThat(service.status(s,"APP").token()).isEqualTo(token);assertThat(service.payload(s,token,0).tables()).contains("ORDERS","CUSTOMER");
            assertThatThrownBy(()->service.generate(s,token,0,true,Locale.KOREAN)).isInstanceOf(Failure.class);
            assertThatThrownBy(()->service.resume(s,token,0,false)).isInstanceOf(Failure.class);
            assertThat(service.resume(s,token,0,true).progress().authorizedUntil()).isEqualTo(1);service.stop(s,token);
            s.metadata().assistant().select(new AiAssistant.Selection("APP","CHANGED"));
            assertThatThrownBy(()->service.resume(s,token,0,true)).isInstanceOf(Failure.class);
            assertThat(fixture.aiCalls).isZero();assertThat(fixture.allReads).isZero();
        }
    }
    @Test void serviceCallsAreAuthorizedOrderedAndFreshDefinitionsAreRequiredToResume(){
        var fixture=new OntologyScopeServiceTest();boolean[] drift={false};int[] calls={0};
        var repository=new OntologyRepository(fixture.jdbc,json,new DatabaseRepository(fixture.jdbc),new TableStructureRepository(fixture.jdbc)){
            @Override public List<Entry> relationshipEntries(String schema,String login,List<String> tables){
                var e=f.customer();return List.of(drift[0]?new Entry("2",2,e.documentId(),e.state(),e.actor(),e.recordedAt(),e.document()):e,f.orders());
            }
        };
        var ai=new AiAssistantRepository(fixture.jdbc){
            @Override public AiAssistant.Profile profile(AiAssistant.Selection selection){return f.profile;}
            @Override public String explain(String owner,String profile,String prompt){calls[0]++;return "{\"relations\":[]}";}
        };
        var profiles=new ProfileHistoryRepository(fixture.jdbc,json,null){@Override public String packageOwner(String user){return "CLOUD";}};
        var service=new OntologyPipelineService(fixture.source,repository,ai,profiles,null,json);
        try(var s=fixture.session()){
            s.metadata().assistant().select(f.profile.selection());var preview=service.preview(s,"APP",List.of("CUSTOMER","ORDERS"));
            service.resume(s,preview.token(),0,true);assertThat(service.generate(s,preview.token(),0,true,Locale.KOREAN).completed()).isEqualTo(1);
            assertThatThrownBy(()->service.generate(s,preview.token(),0,true,Locale.KOREAN)).isInstanceOf(Failure.class);assertThat(calls[0]).isEqualTo(1);
            var next=service.preview(s,"APP",List.of("CUSTOMER","ORDERS"));drift[0]=true;
            assertThatThrownBy(()->service.resume(s,next.token(),0,true)).isInstanceOf(Failure.class);assertThat(calls[0]).isEqualTo(1);
        }
    }
}
