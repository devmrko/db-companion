package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.SemanticRelease.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.*;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic storage/transaction tests; no real DB, AI calls, or business-result claims. */
class SemanticReleaseTest {
    final JsonMapper json=new JsonMapper();final OntologyPipelineTest fixtures=new OntologyPipelineTest();
    final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){
        case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);default->empty(m.getReturnType());});}};
    final JdbcTemplate jdbc=new JdbcTemplate(source);String storage="READY";
    final Map<String,SemanticReleaseRepository.Row> saved=new LinkedHashMap<>();final Map<String,String> kinds=new HashMap<>();
    Head head=new Head(0,"");Preference preference=new Preference(0,"DEFAULT");int physicalChecks;
    final AppRecordRepository records=new AppRecordRepository(jdbc,json){
        @Override public String status(String a,String b){return storage;}
        @Override public void require(String a,String b){if(!storage.equals("READY"))throw SemanticRelease.invalid();}
    };
    final SemanticReleaseRepository repo=new SemanticReleaseRepository(jdbc,json){
        @Override public Head head(String owner){assertThat(owner).isEqualTo("APP");return head;}
        @Override public Preference preference(String owner){return preference;}
        @Override public void preference(String owner,Preference expected,String selection){if(!expected.equals(preference))throw SemanticRelease.invalid();preference=new Preference(expected.revision()+1,selection);}
        @Override public List<Row> list(String owner,String type){return saved.values().stream().filter(r->type.equals(kinds.get(r.id()))).toList();}
        @Override public Row get(String owner,String id){if(!saved.containsKey(id))throw SemanticRelease.invalid();return saved.get(id);}
        @Override public void insert(String owner,String id,String type,Object payload){assertThat(saved).doesNotContainKey(id);saved.put(id,new Row(id,"APP","2026-01-01T00:00:00Z",json.writeValueAsString(payload)));kinds.put(id,type);}
        @Override public Head deploy(String owner,Head expected,String target,String reason){if(!head.equals(expected))throw SemanticRelease.invalid();head=new Head(head.revision()+1,target);insert(owner,UUID.randomUUID().toString(),SemanticRelease.DEPLOY,new Deployment(expected.releaseId(),target,head.revision(),reason));return head;}
    };
    final BusinessGlossary.Term term=new BusinessGlossary.Term(UUID.randomUUID().toString(),1,"매출",List.of("결제 금액"),"기간 거래 합계","",true,"now");
    final BusinessGlossaryRepository glossary=new BusinessGlossaryRepository(jdbc,json){
        @Override public void requireTable(String owner){}
        @Override public void lockTransfer(String owner){}
        @Override public List<BusinessGlossary.Key> keys(String owner){return List.of(new BusinessGlossary.Key(term.id(),1,term.term(),term.aliases()));}
        @Override public BusinessGlossary.Term find(String owner,String id){return term;}
    };
    final OntologyRepository ontology=new OntologyRepository(jdbc,json,new DatabaseRepository(jdbc),new TableStructureRepository(jdbc)){
        @Override public List<Ontology.Entry> relationshipEntries(String a,String b){return List.of(fixtures.customer());}
        @Override public Ontology.Entry entry(String a,String b,int c){return fixtures.customer();}
    };
    final PropertyGraphRepository physical=new PropertyGraphRepository(jdbc){@Override public void verifyMetadata(List<Ontology.Entry> entries){physicalChecks++;}};
    final SemanticReleaseService service=new SemanticReleaseService(source,records,repo,glossary,ontology,physical,json);
    PoolSession session(){return new OntologyReadCacheTest().session();}
    @Test void immutableSnapshotHasHashAndDoesNotDeploy(){try(var s=session()){
        var v=service.create(s,"candidate",true);assertThat(v.content().terms()).containsExactly(term);assertThat(v.hash()).hasSize(64);
        assertThat(service.status(s).head().revision()).isZero();assertThatThrownBy(()->service.active(s)).isInstanceOf(AiAssistant.Failure.class);assertThat(saved).hasSize(1);assertThat(physicalChecks).isEqualTo(1);
        var copy=json.readValue(saved.get(v.id()).payload(),Stored.class);assertThat(copy.read(json)).isEqualTo(v.content());
        assertThatThrownBy(()->new Stored(copy.hash(),copy.body()+" ").read(json)).isInstanceOf(AiAssistant.Failure.class);
    }}
    @Test void explicitPreferenceSurvivesLoginAndOverridesDeployment(){String first,second;
        try(var s=session()){first=service.create(s,"v1",true).id();second=service.create(s,"v2",true).id();service.deploy(s,first,head,"release",true);service.select(s,second,preference,true);assertThat(service.active(s).id()).isEqualTo(second);}
        try(var s=session()){assertThat(service.active(s).id()).isEqualTo(second);assertThat(head.releaseId()).isEqualTo(first);}
    }
    @Test void rollbackChangesPointerNotSnapshotsAndPinnedSessionStaysStable(){try(var s=session()){
        var first=service.create(s,"v1",true);var second=service.create(s,"v2",true);service.deploy(s,first.id(),head,"release",true);service.select(s,"DEFAULT",preference,true);
        service.deploy(s,second.id(),head,"upgrade",true);assertThat(service.active(s).id()).isEqualTo(first.id());
        service.select(s,"DEFAULT",preference,true);assertThat(service.active(s).id()).isEqualTo(second.id());
        service.deploy(s,first.id(),head,"rollback",true);service.select(s,"DEFAULT",preference,true);assertThat(service.active(s).id()).isEqualTo(first.id());
        assertThat(repo.list("APP",SemanticRelease.TYPE)).hasSize(2);assertThat(repo.list("APP",SemanticRelease.DEPLOY)).hasSize(3);
    }}
    @Test void staleDeploymentOrPreferenceIsRejected(){try(var s=session()){
        var v=service.create(s,"v1",true);var old=head;service.deploy(s,v.id(),old,"first",true);
        assertThatThrownBy(()->service.deploy(s,v.id(),old,"stale",true)).isInstanceOf(AiAssistant.Failure.class);
        var pref=preference;service.select(s,v.id(),pref,true);assertThatThrownBy(()->service.select(s,"LIVE",pref,true)).isInstanceOf(AiAssistant.Failure.class);
    }}
    @Test void missingStorageDoesNotInstallAndScopeConsentAreEnforced(){try(var s=session()){
        storage="MISSING";assertThat(service.active(s)).isNull();assertThatThrownBy(()->service.create(s,"v",true)).isInstanceOf(AiAssistant.Failure.class);assertThat(saved).isEmpty();
        storage="READY";assertThatThrownBy(()->service.create(s,"v",false)).isInstanceOf(AiAssistant.Failure.class);
        s.metadata().selectSchema("OTHER");assertThatThrownBy(()->service.create(s,"v",true)).isInstanceOf(AiAssistant.Failure.class);
    }}
    @Test void corruptedSnapshotAndUnknownTermsCannotFallBackToLive(){try(var s=session()){
        var v=service.create(s,"v",true);assertThatThrownBy(()->SemanticReleaseService.verifyTerms(v,List.of(new BusinessGlossary.Term(term.id(),2,"매출",List.of(),"changed","",true,"now")))).isInstanceOf(AiAssistant.Failure.class);
        var row=saved.get(v.id());saved.put(v.id(),new SemanticReleaseRepository.Row(row.id(),row.actor(),row.recordedAt(),json.writeValueAsString(new Stored(v.hash(),"{}"))));
        assertThatThrownBy(()->service.select(s,v.id(),preference,true)).isInstanceOf(AiAssistant.Failure.class);assertThat(preference.revision()).isZero();
    }}
    @Test void frozenSearchIgnoresLiveIndexAndAttachesReleaseIdentity(){try(var s=session()){
        var v=service.create(s,"v1",true);service.select(s,v.id(),preference,true);
        var dictionary=new BusinessGlossaryService(source,glossary,json);
        org.springframework.test.util.ReflectionTestUtils.setField(dictionary,"releases",service);
        var search=dictionary.search(s,"매출 알려줘","P",true);
        assertThat(search.mode()).isEqualTo("RELEASE_EXACT_ALIAS");assertThat(search.hits()).extracting(h->h.term().id()).containsExactly(term.id());
        var snapshot=dictionary.resolve(s,search.question(),"P",new BusinessGlossary.Selection(true,search.id(),List.of(term.id())));
        assertThat(json.readTree(snapshot.source()).path("semanticRelease").path("id").asString()).isEqualTo(v.id());
        var copy=service.detail(s,v.id());assertThat(copy.content()).isEqualTo(v.content());
    }}
    @Test void versionSelectionClearsOldSearchTickets(){try(var s=session()){
        var v=service.create(s,"v1",true);service.select(s,v.id(),preference,true);
        var dictionary=new BusinessGlossaryService(source,glossary,json);org.springframework.test.util.ReflectionTestUtils.setField(dictionary,"releases",service);
        var search=dictionary.search(s,"매출","P",false);service.select(s,"LIVE",preference,true);
        assertThatThrownBy(()->dictionary.resolve(s,"매출","P",new BusinessGlossary.Selection(true,search.id(),List.of(term.id())))).isInstanceOf(AiAssistant.Failure.class);
    }}
    @Test void initializationIsFirstOnlyAndDoesNotRecaptureAfterEdits(){try(var s=session()){
        assertThatThrownBy(()->service.initialize(s,false)).isInstanceOf(AiAssistant.Failure.class);
        var initial=service.initialize(s,true);assertThat(head.releaseId()).isEqualTo(initial.id());
        var again=service.initialize(s,true);assertThat(again).isEqualTo(initial);
        assertThat(repo.list("APP",SemanticRelease.TYPE)).hasSize(1);assertThat(repo.list("APP",SemanticRelease.DEPLOY)).hasSize(1);
        service.select(s,"DEFAULT",preference,true);assertThat(service.active(s)).isEqualTo(initial);
    }}
    @Test void revisionCopiesParentMembersWithoutChangingParentOrDeployment(){try(var s=session()){
        var parent=service.initialize(s,true);var entry=parent.content().entries().getFirst();
        var revised=service.revise(s,parent.id(),"without glossary",List.of(),List.of(new SourceRef(entry.document().source().table(),entry.revision())),true);
        assertThat(revised.id()).isNotEqualTo(parent.id());assertThat(revised.content().parentId()).isEqualTo(parent.id());
        assertThat(revised.content().terms()).isEmpty();assertThat(service.detail(s,parent.id()).content().terms()).containsExactly(term);
        assertThat(head.releaseId()).isEqualTo(parent.id());assertThat(service.catalog(s).terms()).hasSize(1);
    }}
    @Test void revisionRejectsUnknownSourceDuplicateMembersAndEmptySources(){try(var s=session()){
        var parent=service.create(s,"v1",true);var entry=parent.content().entries().getFirst();var sourceRef=new SourceRef(entry.document().source().table(),entry.revision());
        assertThatThrownBy(()->service.revise(s,parent.id(),"bad",List.of(),List.of(new SourceRef(sourceRef.table(),999)),true)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->service.revise(s,parent.id(),"bad",List.of(new TermRef(term.id(),1),new TermRef(term.id(),1)),List.of(sourceRef),true)).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->service.revise(s,parent.id(),"bad",List.of(),List.of(),true)).isInstanceOf(AiAssistant.Failure.class);
        assertThat(repo.list("APP",SemanticRelease.TYPE)).hasSize(1);
    }}
}
