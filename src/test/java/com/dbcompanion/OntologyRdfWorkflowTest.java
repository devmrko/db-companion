package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.service.*;
import com.dbcompanion.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class OntologyRdfWorkflowTest {
    final OntologyPlanSelectionTest f=new OntologyPlanSelectionTest();
    @Test void graphTermsOnlyMapParsedLocalDictionarySourcesNotProseLiteralsOrOtherSchemas(){
        var data=f.fixture.data(f.entries);
        var criteria=List.of("SELECT COUNT(*) FROM APP.A WHERE CODE = :day", "SELECT CODE FROM \"APP\".\"B\"", "SELECT 'A' FROM OTHER.A", "BIZ_FLAG = '1'", "WITH A AS (SELECT CODE FROM APP.B) SELECT CODE FROM A");
        var terms=new ArrayList<BusinessGlossary.Term>();
        for(int i=0;i<criteria.size();i++)terms.add(new BusinessGlossary.Term("term-"+i,i+1,"Metric "+i,List.of(),"Mentions A and B, not a mapping",criteria.get(i),true,"now"));
        var ground=new OntologyQuestionGrounding.Result("question","question",terms);
        var rdf=OntologyRdfSearch.search(data,"all",List.of("standard","business","unreviewed"),"");
        var summary=OntologyRdfWorkflow.summary(data,rdf,ground);
        assertThat(summary.termReferences()).extracting(OntologyRdfWorkflow.TermReference::sources).containsExactly(List.of("A"),List.of("B"),List.of(),List.of(),List.of("B"));
        assertThat(summary.termReferences().getFirst().revision()).isEqualTo(1);
        assertThat(summary.sources()).allMatch(s->s.schema().equals("APP")&&s.versionIri().startsWith("urn:uuid:"));
        assertThat(summary.sources()).allMatch(s->s.columns().stream().noneMatch(c->c.name().equals("EMAIL")));
    }
    @Test void graphRelationshipProvenancePointsToAnActualRdfResource(){
        var fixture=new OntologyInquiryTest();var data=fixture.data(fixture.entries());
        var rdf=OntologyRdfSearch.search(data,"all",List.of("사용자","권역"),"");
        var summary=OntologyRdfWorkflow.summary(data,rdf,null);
        var fk=summary.relations().stream().filter(r->r.origin().equals("FK")).findFirst().orElseThrow();
        assertThat(fk.resourceIri()).endsWith("/table/key/A_B_FK");assertThat(fk.evidence()).contains("DATABASE_CONSTRAINT");
        assertThat(OntologyRdf.export(data.entries().getFirst()).triples()).anyMatch(t->t.subject().equals(fk.resourceIri()));
    }
    @Test void rdfEvidenceTransfersToTestWithoutAiOrExecutionAndRejectsStaleDefinitions(){try(var s=f.session()){
        var state=s.metadata().inquiry();var data=state.data();var ground=state.grounding();
        var rdf=OntologyRdfSearch.search(data,"standard and unreviewed",List.of("standard","unreviewed"),"");
        state.grounded(new OntologyQueryService.GroundedSearch(state.grounded().search(),ground,List.of(),null,rdf,OntologyRdfWorkflow.summary(data,rdf,ground)));
        var service=new OntologyQueryService(f.source,f.repository,null,null,null,null,f.json);
        var glossary=new BusinessGlossaryService(f.source,null,f.json){@Override public void verifyTerms(com.dbcompanion.common.db.PoolSession session,List<BusinessGlossary.Term> terms){}};
        org.springframework.test.util.ReflectionTestUtils.setField(service,"glossary",glossary);
        var saved=service.testEvidence(s,f.id(s),"INDEPENDENT",List.of("A","C"),List.of());
        assertThat(saved.route()).isEqualTo("RDF_INDEPENDENT");assertThat(saved.references()).hasSize(2);
        assertThat(saved.source()).contains("physicalMetadata","dictionaryRules","INDEPENDENT","UNION ALL");
        assertThat(f.json.readTree(saved.source()).path("context").path("approvedDefinitions").toString()).doesNotContain("unreviewed");
        assertThat(s.metadata().aiTest().evidence().resolve(true,saved.hash(),saved.question())).isSameAs(saved);
        assertThatThrownBy(()->s.metadata().aiTest().evidence().resolve(true,saved.hash(),"changed")).isInstanceOf(AiAssistant.Failure.class);
        f.changed=true;assertThatThrownBy(()->service.testEvidence(s,f.id(s),"INDEPENDENT",List.of("A","C"),List.of())).isInstanceOf(Failure.class);
    }}
    @Test void selectedDraftMetadataFlowsThroughSqlReviewAndExplicitOneUseExecution(){try(var s=f.session()){
        var state=s.metadata().inquiry();var data=state.data();var ground=state.grounding();
        var rdf=OntologyRdfSearch.search(data,"standard and unreviewed",List.of("standard","unreviewed"),"");
        state.grounded(new OntologyQueryService.GroundedSearch(state.grounded().search(),ground,List.of(),null,rdf,OntologyRdfWorkflow.summary(data,rdf,ground)));
        var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","LLM"),"oci","test","v1");
        var ai=new AiAssistantRepository(new JdbcTemplate(f.source)){@Override public AiAssistant.Profile profile(AiAssistant.Selection selection){return profile;}};
        var profiles=new ProfileHistoryRepository(new JdbcTemplate(f.source),f.json,null){@Override public String packageOwner(String owner){return "SYS";}};
        var executions=new java.util.concurrent.atomic.AtomicInteger();
        var queries=new OntologyQueryRepository(new JdbcTemplate(f.source),f.json){
            @Override public void profileScope(String name,String schema,List<Entry> entries){assertThat(entries).hasSize(2);}
            @Override public void localTables(List<Entry> entries){}
            @Override public String showsql(String owner,String profile,String prompt,String attributes){assertThat(prompt).contains("dictionaryRules","INDEPENDENT").doesNotContain("urn:uuid:");return "SELECT 'standard' AS metric, COUNT(DISTINCT a.CODE) AS n FROM A a UNION ALL SELECT 'other' AS metric, COUNT(DISTINCT c.CODE) AS n FROM C c";}
            @Override public OntologyInquiry.Rows execute(OntologyInquiry.Execution v,String actor){executions.incrementAndGet();return new OntologyInquiry.Rows(v.search().id(),v.draft().sql(),v.draft().hash(),actor,"now",List.of("METRIC","N"),List.of(List.of(new OntologyInquiry.Cell("standard",false),new OntologyInquiry.Cell("3",false))),false);}
        };
        var graphs=new PropertyGraphRepository(new JdbcTemplate(f.source)){@Override public void verifyMetadata(List<Entry> entries){}};
        var service=new OntologyQueryService(f.source,f.repository,ai,profiles,queries,graphs,f.json);
        var glossary=new BusinessGlossaryService(f.source,null,f.json){@Override public void verifyTerms(com.dbcompanion.common.db.PoolSession session,List<BusinessGlossary.Term> terms){}};
        org.springframework.test.util.ReflectionTestUtils.setField(service,"glossary",glossary);s.metadata().assistant().select(profile.selection());
        var selected=service.selectRdf(s,f.id(s),"INDEPENDENT",List.of("A","C"),List.of());
        assertThat(selected.evidence()).noneMatch(e->e.kind().equals("DEFINITION")&&e.source().equals("C"));
        var preview=service.preview(s,selected.id(),"SQL","INDEPENDENT",Locale.ENGLISH);
        assertThat(preview.request().source()).contains("Preserve dictionary comparison predicates exactly");
        var sql=service.generate(s,preview.request().token(),true).sql();assertThat(sql.executable()).isTrue();assertThat(executions).hasValue(0);
        assertThatThrownBy(()->service.execute(s,sql.token(),false)).isInstanceOf(Failure.class);assertThat(executions).hasValue(0);
        assertThat(service.execute(s,sql.token(),true).rows()).hasSize(1);assertThat(executions).hasValue(1);
        assertThatThrownBy(()->service.execute(s,sql.token(),true)).isInstanceOf(Failure.class);assertThat(executions).hasValue(1);
    }}
    @Test void unknownSourcesUnverifiedJoinsAndIndependentJoinInjectionAreBlocked(){try(var s=f.session()){
        var data=s.metadata().inquiry().data();var rdf=OntologyRdfSearch.search(data,"all",List.of("standard","business","unreviewed"),"");
        var ground=new OntologyQueryService.GroundedSearch(s.metadata().inquiry().grounded().search(),s.metadata().inquiry().grounding(),List.of(),null,rdf,OntologyRdfWorkflow.summary(data,rdf,null));
        assertThatThrownBy(()->OntologyRdfWorkflow.select(data,ground,List.of("FORGED"),List.of(),"INDEPENDENT")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyRdfWorkflow.select(data,ground,List.of("A","B"),List.of("C1"),"JOIN")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyRdfWorkflow.select(data,ground,List.of("A","B"),List.of(),"JOIN")).isInstanceOf(Failure.class);
        var selected=OntologyRdfWorkflow.select(data,ground,List.of("A","B"),List.of(),"INDEPENDENT");
        assertThatThrownBy(()->ReviewedSql.check("SELECT a.CODE FROM A a JOIN B b ON a.CODE=b.CODE",ReviewedSql.scope(selected,f.entries))).isInstanceOf(Failure.class);
    }}
}
