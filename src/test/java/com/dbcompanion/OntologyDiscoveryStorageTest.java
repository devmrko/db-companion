package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyDiscoveryArchive.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyDiscoveryStorageTest {
    static class MemoryArchive extends OntologyDiscoveryRepository {
        final Map<String,Run> runs=new LinkedHashMap<>();final Map<String,Call> receipts=new LinkedHashMap<>();boolean available=true;
        MemoryArchive(JdbcTemplate jdbc,JsonMapper json){super(jdbc,null,json);}
        @Override public void require(String schema,String login){QueryArchive.owner(schema,login);if(!available)throw new Failure(409,"archive.notReady");}
        @Override public boolean exists(String schema,String id){return runs.containsKey(id);}
        @Override public void saveRun(Run run){runs.putIfAbsent(run.id(),run);}
        @Override public Run run(String schema,String database,String id){var r=runs.get(id);if(r==null||!r.schema().equals(schema)||!r.database().equals(database))throw new Failure(409,"stale");return r;}
        @Override public void claim(String schema,Receipt r){String id=OntologyDiscoveryArchive.callId(r.runId(),r.index());if(receipts.putIfAbsent(id,new Call(id,"REQUESTED",r))!=null)throw new Failure(409,"stale");}
        @Override public void finish(String schema,Receipt r,String state){var id=OntologyDiscoveryArchive.callId(r.runId(),r.index());if(!receipts.get(id).state().equals("REQUESTED"))throw new Failure(409,"stale");receipts.put(id,new Call(id,state,r));}
        @Override public List<Call> calls(String schema,String id){return receipts.values().stream().filter(c->c.receipt().runId().equals(id)).toList();}
        @Override public Call call(String schema,String token,int index){return receipts.get(OntologyDiscoveryArchive.callId(token,index));}
        @Override public void recovered(String schema,Receipt r){var id=OntologyDiscoveryArchive.callId(r.runId(),r.index());receipts.put(id,new Call(id,"SUCCEEDED",r));}
    }
    final OntologyScopeServiceTest tx=new OntologyScopeServiceTest();final OntologyPipelineTest f=new OntologyPipelineTest();
    final JsonMapper json=new JsonMapper();final MemoryArchive archive=new MemoryArchive(tx.jdbc,json);
    final Map<String,Entry> latest=new LinkedHashMap<>(),versions=new HashMap<>();boolean failSave,failAi;int calls;
    String output=f.response("ORDERS","BUYER_NO");
    OntologyDiscoveryStorageTest(){for(var e:List.of(f.customer(),f.orders()))put(e);}
    void put(Entry e){String name=e.document().source().table();latest.put(name,e);versions.put(name+":"+e.revision(),e);}
    final OntologyRepository repository=new OntologyRepository(tx.jdbc,json,new DatabaseRepository(tx.jdbc),new TableStructureRepository(tx.jdbc)){
        @Override public List<Entry> relationshipEntries(String schema,String login,List<String> tables){return tables.stream().sorted().map(latest::get).toList();}
        @Override public Entry entry(String schema,String table,int revision){return revision==0?latest.get(table):versions.get(table+":"+revision);}
        @Override public Entry append(String schema,String table,int expected,String state,Document d){if(failSave)throw new IllegalStateException("simulated store failure");var before=latest.get(table);assertThat(before.revision()).isEqualTo(expected);Ontology.parse(json.writeValueAsString(d),json);var e=new Entry(""+(expected+1),expected+1,before.documentId(),state,"APP","now",d);put(e);return e;}
    };
    final AiAssistantRepository ai=new AiAssistantRepository(tx.jdbc){
        @Override public AiAssistant.Profile profile(AiAssistant.Selection selected){return f.profile;}
        @Override public String explain(String owner,String profile,String prompt){calls++;assertThat(archive.receipts.values()).anyMatch(c->c.state().equals("REQUESTED"));if(failAi)throw new IllegalStateException("simulated interrupted response");return output;}
    };
    final ProfileHistoryRepository profiles=new ProfileHistoryRepository(tx.jdbc,json,null){@Override public String packageOwner(String user){return "CLOUD";}};
    final OntologyPipelineService service=new OntologyPipelineService(tx.source,repository,ai,profiles,null,json,archive);
    PoolSession session(){var s=tx.session();s.metadata().assistant().select(f.profile.selection());return s;}
    OntologyDiscovery.Preview prepare(PoolSession s){var p=service.preview(s,"APP",List.of("CUSTOMER","ORDERS"));return service.resume(s,p.token(),0,true,true);}
    @Test void candidatesAreSavedInVersionedOntologyAndRdfWithoutApprovalAndReopenAfterLogout(){
        String token;try(var s=session()){var p=prepare(s);token=p.token();var result=service.generate(s,token,0,true,Locale.KOREAN);assertThat(result.candidates()).isEqualTo(1);assertThat(result.completed()).isEqualTo(1);}
        var stored=latest.get("ORDERS");assertThat(stored.revision()).isEqualTo(2);assertThat(stored.document().links()).hasSize(1).allMatch(l->l.status().equals("CANDIDATE"));
        assertThat(OntologyRdf.render(stored)).contains("CANDIDATE","BUYER_NO");assertThat(versions.get("ORDERS:1").document().links()).isEmpty();
        var analysis=f.data(List.copyOf(latest.values()));assertThat(analysis.relations()).anyMatch(r->r.status().equals("CANDIDATE")&&r.origin().equals("AI"));
        assertThat(PropertyGraph.build("APP","G",List.copyOf(latest.values()),analysis).edges()).isZero();
        try(var s=session()){var restored=service.restore(s,"APP",token);assertThat(restored.progress().completed()).isEqualTo(1);assertThat(restored.progress().candidates()).isEqualTo(1);assertThat(restored.progress().done()).isTrue();}
        assertThat(calls).isEqualTo(1);
    }
    @Test void aBadItemDoesNotDiscardGoodMappingsAndRawResponseIsRetained(){
        var good=json.readTree(output).path("relations").get(0);var bad=json.readTree(f.response("OTHER","BUYER_NO")).path("relations").get(0);output=json.writeValueAsString(Map.of("relations",List.of(good,bad,good)));
        try(var s=session()){var p=prepare(s);var step=service.generate(s,p.token(),0,true,Locale.KOREAN);assertThat(step.candidates()).isEqualTo(1);assertThat(step.failed()).containsExactly(1);assertThat(step.completed()).isZero();
            var receipt=archive.call("APP",p.token(),0);assertThat(receipt.state()).isEqualTo("SUCCEEDED");assertThat(receipt.receipt().raw()).isEqualTo(output);assertThat(receipt.receipt().issues()).extracting("code").containsExactly("TABLE","DUPLICATE");}
        assertThat(latest.get("ORDERS").document().links()).hasSize(1);
    }
    @Test void storageIsRequiredBeforeAnyPaidCallAndClaimsPreventDuplicateCalls(){try(var s=session()){
        archive.available=false;var preview=service.preview(s,"APP",List.of("CUSTOMER","ORDERS"));assertThatThrownBy(()->service.resume(s,preview.token(),0,true)).isInstanceOf(Failure.class);assertThat(calls).isZero();
        archive.available=true;service.resume(s,preview.token(),0,true);service.generate(s,preview.token(),0,true,Locale.KOREAN);assertThatThrownBy(()->service.generate(s,preview.token(),0,true,Locale.KOREAN)).isInstanceOf(Failure.class);assertThat(calls).isEqualTo(1);
    }}
    @Test void failedAiIsDurablyMarkedAndNeverClaimedAsSuccessful(){try(var s=session()){
        failAi=true;var p=prepare(s);var step=service.generate(s,p.token(),0,true,Locale.KOREAN);assertThat(step.completed()).isZero();assertThat(step.failed()).containsExactly(1);
        assertThat(archive.call("APP",p.token(),0).state()).isEqualTo("CHECK_REQUIRED");assertThat(latest.get("ORDERS").document().links()).isEmpty();
        s.metadata().ontology().clear("APP");assertThat(service.restore(s,"APP",p.token()).progress().nextIndex()).isEqualTo(1);assertThat(calls).isEqualTo(1);
    }}
    @Test void receivedResponseSurvivesSaveFailureAndCanBeSavedWithoutAnotherAiCall(){try(var s=session()){
        failSave=true;var p=prepare(s);var step=service.generate(s,p.token(),0,true,Locale.KOREAN);assertThat(step.failed()).containsExactly(1);var receipt=archive.call("APP",p.token(),0);assertThat(receipt.state()).isEqualTo("CHECK_REQUIRED");assertThat(receipt.receipt().stage()).isEqualTo("SAVE");assertThat(receipt.receipt().raw()).isEqualTo(output);
        failSave=false;assertThatThrownBy(()->service.recover(s,"APP",p.token(),0,false)).isInstanceOf(Failure.class);var restored=service.recover(s,"APP",p.token(),0,true);assertThat(restored.progress().completed()).isEqualTo(1);assertThat(latest.get("ORDERS").document().links()).hasSize(1);assertThat(calls).isEqualTo(1);
    }}
    @Test void fullRunContinuesAfterRecordedFailuresButNeverReplaysTheirIndices(){
        var state=new OntologyPipeline.State();var batch=new OntologyDiscovery.Batch(List.of("A","B"),"{}");var batches=Collections.nCopies(66,batch);String id=UUID.randomUUID().toString();var now=Instant.now();state.prepare(new OntologyDiscovery.Plan(id,"APP",f.profile,batches,2,now.plusSeconds(60)),List.of());state.authorize(id,0,true,"APP",now,true);
        for(int i=0;i<66;i++){state.begin(id,i,true,"APP",Instant.now());state.recorded(List.of(),i!=0&&i!=5);}
        assertThat(state.progress().completed()).isEqualTo(64);assertThat(state.progress().failed()).containsExactly(1,6);assertThat(state.progress().nextIndex()).isEqualTo(66);assertThat(state.progress().done()).isTrue();
    }
    @Test void conditionsAreRetainedAsNotesAndNeverPromotedToApproval(){
        var node=json.readTree(output);((tools.jackson.databind.node.ObjectNode)node.path("relations").get(0)).put("condition","동일 기준일로 제한하고 대상 코드를 중복 제거한다.");
        var result=OntologyDiscovery.inspect(json.writeValueAsString(node),OntologyDiscovery.batches(List.copyOf(latest.values()),json).getFirst(),f.data(List.copyOf(latest.values())),f.profile,json);
        assertThat(result.relations().getFirst().condition()).contains("중복 제거");assertThat(result.relations().getFirst().status()).isEqualTo("CANDIDATE");
    }
}
