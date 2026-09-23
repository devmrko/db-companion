package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.QueryArchive.*;
import com.dbcompanion.repository.AppRecordRepository;
import com.dbcompanion.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class QueryArchiveTest {
    final String id="7899ba86-a54c-4032-bcaf-a28c3c844bc1";
    final JsonMapper json=new JsonMapper();
    final OntologyInquiryTest fixture=new OntologyInquiryTest();
    @Test void sharedRecordsDoNotAlterLegacyHistoryOrCreatePerQuestionTables(){
        String sql=AppRecordSql.create("Mixed'Owner");assertThat(sql).contains("\"Mixed'Owner\".\"DBC_APP_RECORD\"","RECORD_TYPE VARCHAR2(32 CHAR)","PAYLOAD IS JSON","SESSION_USER","TIMESTAMP WITH TIME ZONE","RECORD_ID VARCHAR2(36 CHAR) NOT NULL UNIQUE").doesNotContain("ALTER TABLE","DROP ","DBC_AI_HISTORY");
        assertThat(AiHistorySql.create("APP")).doesNotContain("ONTOLOGY_QUERY");
        assertThat(AppRecordRepository.layout()).hasSize(9).contains("ACTOR:VARCHAR2:N:128","PAYLOAD:CLOB:N:0");
    }
    @Test void constructAcceptsOnlyUuidAndKeepsNamedGraphsSeparate(){
        assertThat(RdfQuerySql.construct(id,false)).isEqualTo("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:uuid:"+id+"/source> { ?s ?p ?o } }");
        assertThat(RdfQuerySql.construct(id,true)).contains("/result>").doesNotContain("/source>");
        String sql=RdfQuerySql.select("O'WNER",id,false);assertThat(sql).contains("'O''WNER'","MDSYS.SEM_MODELS('DBC_QUERY')","CONSTRUCT_UNIQUE=T CONSTRUCT_STRICT=T","FETCH FIRST 10001 ROWS ONLY","RDFCLBT").doesNotContain("SERVICE ","SEM_RULEBASES","UPDATE ");
        for(String bad:List.of("","1-1-1-1-1",id.toUpperCase(Locale.ROOT),id+"'> } SERVICE <http://bad> {", "null"))assertThatThrownBy(()->RdfQuerySql.construct(bad,false)).isInstanceOf(Ontology.Failure.class);
        assertThat(RdfQuerySql.insert("A\"B")).contains("\"A\"\"B\".\"DBC_RDF#RDFT_DBC_QUERY\"").contains("(?,?,?,?,?,?)");
    }
    @Test void deterministicEvidencePreservesMappingsWithoutDraftMeaningOrSensitiveData(){
        var search=fixture.search();var route=search.routes().stream().filter(r->r.tables().size()==2).findFirst().orElseThrow();
        var chosen=OntologyInquiry.chooseRoute(search,route.id());var model=QueryArchiveRdf.evidence(chosen,fixture.entries(),json);var text=QueryArchiveRdf.text(model);
        assertThat(text).contains("SelectedColumnMapping","/column/CODE>","DATABASE_CONSTRAINT","SKOS".toLowerCase(Locale.ROOT)).doesNotContain("EMAIL","secret description","업무 정의 사용자","sameAs","owl#");
        assertThat(QueryArchiveRdf.parse(text)).isEqualTo(model);
        var reversed=new org.eclipse.rdf4j.model.impl.LinkedHashModel();var rows=new ArrayList<>(model);Collections.reverse(rows);reversed.addAll(rows);
        assertThat(QueryArchiveRdf.hash(reversed)).isEqualTo(QueryArchiveRdf.hash(model));
        assertThat(QueryArchiveRdf.fromRows(QueryArchiveRdf.rows(model))).isEqualTo(model);
    }
    @Test void unselectedRelationshipsDoNotLeakIntoSavedGraph(){
        var search=fixture.search();var only=OntologyInquiry.choose(search,List.of("M1"));var entries=OntologyInquiry.selected(only,fixture.data(fixture.entries()));
        var text=QueryArchiveRdf.text(QueryArchiveRdf.evidence(only,entries,json));
        assertThat(text).contains("CODE").doesNotContain("SelectedColumnMapping","EMAIL","업무 정의","A_B_FK");
    }
    @Test void rdfTermsAreNeverSilentlyTruncatedOrInterpretedAsInstructions(){
        var safe="<urn:s> <urn:p> \"line\\nquote\\\"\\\\한글\" .";
        assertThat(QueryArchiveRdf.parse(safe)).hasSize(1);
        for(String invalid:List.of("", "not rdf", "_:b <urn:p> <urn:o> .", "<urn:s> <urn:p> \""+"가".repeat(1400)+"\" ."))assertThatThrownBy(()->QueryArchiveRdf.parse(invalid)).isInstanceOf(Ontology.Failure.class);
        var a=QueryArchiveRdf.parse("<urn:s> <urn:p> \"one\" .");var b=QueryArchiveRdf.parse("<urn:s> <urn:p> \"two\" .");
        assertThatThrownBy(()->QueryArchiveRdf.same(a,b)).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->QueryArchiveRdf.parse("x".repeat(QueryArchive.MAX_JSON+1))).isInstanceOf(Ontology.Failure.class);
    }
    @Test void pendingSaveIsOwnerScopedConfirmedExpiringAndOneUse(){
        var now=Instant.now();var state=new State();var search=fixture.search();
        var preview=new Preview(id,"token","APP","question",RdfQuerySql.construct(id,false),"rdf",json.createObjectNode(),1,now.plusSeconds(600));
        var pending=new Pending(preview,search,fixture.entries());state.prepare(pending);
        assertThatThrownBy(()->state.consume("token","APP",false,now)).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->state.consume("token","OTHER",true,now)).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->state.consume("wrong","APP",true,now)).isInstanceOf(Ontology.Failure.class);
        assertThat(state.consume("token","APP",true,now)).isEqualTo(pending);
        assertThatThrownBy(()->state.consume("token","APP",true,now)).isInstanceOf(Ontology.Failure.class);
        state.prepare(pending);assertThatThrownBy(()->state.consume("token","APP",true,now.plusSeconds(600))).isInstanceOf(Ontology.Failure.class);
        QueryArchive.owner("APP","APP");assertThatThrownBy(()->QueryArchive.owner("OTHER","APP")).isInstanceOf(Ontology.Failure.class);
    }
    @Test void statusCachingRefreshAndSchemaChangeClearPendingState(){
        var s=new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER"));var count=new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<Status> load=()->{count.incrementAndGet();return new Status("READY","READY",true,true,"DATA","now","");};
        s.queryArchive().status("APP",false,load);s.queryArchive().status("APP",false,load);assertThat(count.get()).isEqualTo(1);
        s.queryArchive().status("APP",true,load);assertThat(count.get()).isEqualTo(2);
        s.selectSchema("OTHER");s.queryArchive().status("APP",false,load);assertThat(count.get()).isEqualTo(3);
    }
    @Test void outcomeIsBoundToTheGeneratedPathAndClearedOnInvalidation(){
        var state=new OntologyInquiry.State();var value=new OntologyInquiry.Outcome("ANSWER",new OntologyInquiry.Answer("s","INSUFFICIENT",List.of(),"no rows","APP.P","now"),null);
        state.outcome("P1",value);assertThat(state.outcome("P1")).isEqualTo(value);assertThat(state.outcome("P2")).isNull();state.invalidate();assertThat(state.outcome("P1")).isNull();
    }
    @Test void setupHasNoPrivilegeGrantJvmEnablementOrDestructiveCleanup(){
        var sql=RdfQuerySql.setupPreview("APP","DATA");assertThat(sql).contains("tablespace_name=>'DATA'","network_owner=>'APP'","network_name=>'DBC_RDF'","rdf_graph_name=>'DBC_QUERY'","table_name=>NULL").doesNotContain("GRANT ","DBMS_CLOUD_ADMIN","DROP ","TRUNCATE","COMMIT");
    }
    @Test void preservesNativeErrorCodeAndLocationForDiagnosis(){
        var error=new IllegalStateException("statement",new java.sql.SQLException("ORA-01031: insufficient privileges\nORA-06512: at MDSYS.SEM_APIS, line 12","42000",1031));
        assertThat(OntologyArchiveService.detail(error)).contains("Oracle code=1031","ORA-01031: insufficient privileges","line 12");
    }
}
