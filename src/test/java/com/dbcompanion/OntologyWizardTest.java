package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyWizard.*;
import com.dbcompanion.repository.OntologyWizardRepository;
import com.dbcompanion.service.OntologyRdf;
import java.io.StringReader;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.eclipse.rdf4j.rio.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyWizardTest {
    final JsonMapper json=new JsonMapper();
    Entry entry(){
        var source=new Snapshot("D","APP","T",null,List.of(new ColumnInfo(1,"CODE","VARCHAR2(40 BYTE)","N",null),new ColumnInfo(2,"AMOUNT","NUMBER(12,2)","Y",null),new ColumnInfo(3,"EMAIL","VARCHAR2(100)","Y",null)),List.of(),"now");
        return new Entry("1",1,"497c24e0-3b91-4083-ac24-550eea0a0821","DRAFT","APP","now",new Document(1,source,Ontology.initial(source),"CATALOG",null));
    }
    Sample sample(){var e=entry();return OntologyWizard.sanitize(e,OntologyWizard.select(e,List.of("CODE"),true,10),List.of(List.of("US"),List.of("KR")),Instant.EPOCH);}
    String output(){return """
        {"columns":[{"column":"CODE","description":"Country code; requires confirmation","label":"Country","aliases":["Location"],"unit":"","role":"dimension","assessment":"UNKNOWN","reason":"Short textual codes","uncertainty":"Confirm the code dictionary"}]}
        """;}
    @Test void legacyJsonWithoutDefinitionStillLoadsAndKeepsFormatOne(){
        String old=Ontology.json(entry().document(),json).replace(",\"definition\":null","");
        var parsed=Ontology.parse(old,json);assertThat(parsed).isEqualTo(entry().document());assertThat(parsed.meaning().columns().get("CODE").definition()).isNull();
    }
    @Test void onlyBoundedExplicitNonSensitiveSelectionIsAllowed(){
        var e=entry();assertThat(OntologyWizard.select(e,List.of("CODE"),true,10)).hasSize(1);
        assertThatThrownBy(()->OntologyWizard.select(e,List.of("CODE"),false,10)).isInstanceOf(Failure.class);
        for(var names:List.of(List.<String>of(),List.of("CODE","CODE"),List.of("EMAIL"),List.of("UNKNOWN")))assertThatThrownBy(()->OntologyWizard.select(e,names,true,10)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyWizard.select(e,List.of("CODE"),true,100)).isInstanceOf(Failure.class);
    }
    @Test void unsupportedTypesAndSecretNamesAreBlocked(){
        for(String type:List.of("CLOB","BLOB","VECTOR","XMLTYPE","TIMESTAMP(6) WITH TIME ZONE","USER_TYPE"))assertThat(OntologyWizard.supported(type)).isFalse();
        for(String type:List.of("VARCHAR2(40 BYTE)","NUMBER(12,2)","TIMESTAMP(6)","DATE","BINARY_DOUBLE"))assertThat(OntologyWizard.supported(type)).isTrue();
        for(String name:List.of("PASSWORD","ACCESS_TOKEN","USER_NAME","EMAIL","PRIVATE_KEY","PHONE_NO"))assertThat(OntologyWizard.blocked(new ColumnInfo(1,name,"NUMBER","Y",null),new ColumnMeaning("","UNKNOWN"))).isEqualTo("sensitive");
        assertThat(OntologyWizard.blocked(entry().document().source().columns().getFirst(),new ColumnMeaning("","SENSITIVE"))).isEqualTo("sensitive");
    }
    @Test void sanitizationRemovesEntireColumnsNotJustMatchingRows(){
        var e=entry();var cols=OntologyWizard.select(e,List.of("CODE","AMOUNT"),true,10);
        var s=OntologyWizard.sanitize(e,cols,List.of(List.of("US","20"),List.of("person@example.test","30")),Instant.EPOCH);
        assertThat(s.excluded()).containsExactly("CODE");assertThat(s.columns()).extracting(SampleColumn::name).containsExactly("AMOUNT");
        assertThat(OntologyWizard.payload(s,json)).doesNotContain("CODE","US","person@");
        assertThatThrownBy(()->OntologyWizard.sanitize(e,List.of(cols.getFirst()),List.of(List.of("+82 10 1234 5678")),Instant.EPOCH)).isInstanceOf(Failure.class);
    }
    @Test void nullAndLiteralNullAreDifferentAndUnicodeTruncationIsValid(){
        var e=entry();var s=OntologyWizard.sanitize(e,List.of(e.document().source().columns().getFirst()),Arrays.asList(Arrays.asList((String)null),List.of("[NULL]"),List.of("😀".repeat(201))),Instant.EPOCH);
        assertThat(s.columns().getFirst().values().get(0)).isNull();assertThat(s.columns().getFirst().values().get(1)).isEqualTo("[NULL]");
        assertThat(s.columns().getFirst().values().get(2)).isEqualTo("😀".repeat(200)+"…");assertThat(s.columns().getFirst().truncated()).isEqualTo(1);
        assertThat(s.columns().getFirst().nulls()).isEqualTo(1);assertThat(OntologyWizard.payload(s,json)).contains("null");
    }
    @Test void emptyTablesRemainZeroSamplesNotInventedRows(){var e=entry();var s=OntologyWizard.sanitize(e,List.of(e.document().source().columns().getFirst()),List.of(),Instant.EPOCH);assertThat(s.rows()).isZero();assertThat(s.columns().getFirst().values()).isEmpty();}
    @Test void sqlQuotesIdentifiersAndReadsOnlySelectedColumnsWithStopKey(){
        var columns=List.of(new ColumnInfo(1,"a\"b","VARCHAR2(10)","Y",null),new ColumnInfo(2,"N","NUMBER","N",null),new ColumnInfo(3,"D","DATE","Y",null));
        String sql=OntologyWizardRepository.sampleSql("a\"b","T",columns);
        assertThat(sql).contains("SUBSTR(\"a\"\"b\",1,201)","FROM \"a\"\"b\".\"T\" WHERE ROWNUM <= ?","NLS_NUMERIC_CHARACTERS").doesNotContain("SELECT *","ORDER BY","DBMS_RANDOM","UPDATE","EMAIL");
    }
    @Test void outputIsStrictAndCannotInventColumnsOrExecutableFields(){
        assertThat(OntologyWizard.parse(output(),sample(),"APP.P",json)).hasSize(1);
        for(String bad:List.of("{}",output().replace("\"CODE\"","\"NEW\""),output().replace("\"UNKNOWN\"","\"CERTAIN\""),output().replace("\"role\":\"dimension\"","\"sql\":\"SELECT 1\""),"```json\n"+output()+"```"))assertThatThrownBy(()->OntologyWizard.parse(bad,sample(),"APP.P",json)).isInstanceOf(Failure.class);
    }
    @Test void recommendationsRequireConciseDefinitionsAndSeparateUncertainty(){
        OntologyWizard.concise("판매·품질·서비스·원가·경영 등 사용자의 업무 역할 구분.","역할 범주의 코드 패턴.","공식 코드 목록 확인 필요.");
        OntologyWizard.concise("실적을 바탕으로 산정한 추정원가.","","");
        OntologyWizard.concise("", "", "뜻을 확인할 수 없음.");
        OntologyWizard.concise("😀".repeat(80),"근거","");
        for(String value:List.of("가".repeat(81),"첫 줄\n둘째 줄","역할 코드로 보이며 구분을 나타냄","역할 범주로 추정됨","A field that appears to represent a role"))assertThatThrownBy(()->OntologyWizard.concise(value,"","")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyWizard.concise("역할","가".repeat(161),"")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyWizard.concise("역할","","첫째\n둘째")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyWizard.parse(output().replace("Country code; requires confirmation","사용자의 업무 역할을 나타내는 코드성 텍스트로 보이며 역할 범주를 담는 항목으로 추정됨"),sample(),"APP.P",json)).isInstanceOf(Failure.class);
    }
    @Test void promptRequiresNounPhraseWithoutHidingUncertainty(){
        var profile=new AiAssistant.Profile(new AiAssistant.Selection("APP","P"),"oci","m","v");
        var preview=new AiAssistant.Preview("t","T",profile,"{}",2,false,Instant.now());
        for(String language:List.of("ko","en","zh","ja")){
            String prompt=OntologyWizard.prompt(new AiAssistant.Draft(preview,"APP",language,"ontology-wizard"));
            assertThat(prompt).contains("at most 80 Unicode characters","end with a noun phrase","uncertainty ONLY","at most 160 Unicode characters","do not fabricate certainty");
        }
    }
    @Test void sampledIdentifiersMustNotBeEchoedInStoredEvidence(){
        var e=entry();var s=OntologyWizard.sanitize(e,List.of(e.document().source().columns().getFirst()),List.of(List.of("customer-identifier")),Instant.EPOCH);
        assertThatThrownBy(()->OntologyWizard.parse(output().replace("Short textual codes","customer-identifier"),s,"APP.P",json)).isInstanceOf(Failure.class);
    }
    @Test void onlyReviewedSelectedEditsMergeAndPreserveOtherMeanings(){
        var e=entry();var records=OntologyWizard.parse(output(),sample(),"APP.P",json);var p=new Proposal("t","APP","T",1,records,Instant.now().plusSeconds(10));
        var m=OntologyWizard.merge(e,p,List.of(new Edit("CODE","Edited explanation","Edited label",List.of("Nation"),"","dimension")));
        assertThat(m.columns().get("AMOUNT")).isEqualTo(e.document().meaning().columns().get("AMOUNT"));assertThat(m.columns().get("CODE").description()).isEqualTo("Edited explanation");
        assertThat(m.columns().get("CODE").sensitivity()).isEqualTo("UNKNOWN");assertThat(m.columns().get("CODE").definition().reason()).isEqualTo("Short textual codes");
        assertThatThrownBy(()->OntologyWizard.merge(e,p,List.of())).isInstanceOf(Failure.class);
        assertThatThrownBy(()->OntologyWizard.merge(e,p,List.of(new Edit("EMAIL","x","x",List.of(),"","")))).isInstanceOf(Failure.class);
    }
    @Test void extendedMeaningRoundTripsAndRdfParsesWithoutBusinessAssertions() throws Exception {
        var e=entry();var p=new Proposal("t","APP","T",1,OntologyWizard.parse(output(),sample(),"APP.P",json),Instant.now().plusSeconds(10));
        var meaning=OntologyWizard.merge(e,p,List.of(new Edit("CODE","Meaning","Country",List.of("Nation"),"","dimension")));
        var doc=new Document(1,e.document().source(),meaning,"AI_WIZARD_REVIEWED","APP.P");assertThat(Ontology.parse(Ontology.json(doc,json),json)).isEqualTo(doc);
        String ttl=OntologyRdf.render(new Entry("2",2,e.documentId(),"DRAFT","APP","now",doc));
        assertThat(ttl).contains("rdfs:label \"Country\"","dbc:alias \"Nation\"","dbc:DefinitionReview","dbc:sampleRows 2","DRAFT").doesNotContain("owl:","US","KR");
        assertThat(Rio.parse(new StringReader(ttl),"",RDFFormat.TURTLE)).isNotEmpty();
        var withDefinition=new Entry("2",2,e.documentId(),"DRAFT","APP","now",doc);
        assertThat(Ontology.suggestion(withDefinition,"{\"concept\":\"\",\"description\":\"\",\"columns\":{\"CODE\":\"revised text\"}}",json).columns().get("CODE").definition()).isEqualTo(meaning.columns().get("CODE").definition());
    }
    @Test void cancelExpiryAndVersionMismatchConsumeServerState(){
        var s=new OntologyWizard.State();var p=new Proposal("t","APP","T",1,List.of(),Instant.now().plusSeconds(10));s.propose(p);s.cancel("different");
        assertThatThrownBy(()->s.use("t","APP","T",2,Instant.now())).isInstanceOf(Failure.class);
        assertThatThrownBy(()->s.use("t","APP","T",1,Instant.now())).isInstanceOf(Failure.class);
        s.propose(p);s.cancel("t");assertThatThrownBy(()->s.use("t","APP","T",1,Instant.now())).isInstanceOf(Failure.class);
        s.propose(new Proposal("t","APP","T",1,List.of(),Instant.EPOCH));assertThatThrownBy(()->s.use("t","APP","T",1,Instant.now())).isInstanceOf(Failure.class);
    }
    @Test void assistantCancellationErasesRawDraftAndPurposeIsIsolated(){
        var s=new AiAssistant.State();var selection=new AiAssistant.Selection("APP","P");s.select(selection);var now=Instant.now();
        var p=s.prepare("APP",new AiAssistant.Profile(selection,"oci","m","v"),"T","RAW",false,"ko",now,"ontology-wizard");
        assertThatThrownBy(()->s.consume(p.token(),true,"APP",now,"ontology")).isInstanceOf(AiAssistant.Failure.class);
        s.discard(p.token());assertThatThrownBy(()->s.consume(p.token(),true,"APP",now,"ontology-wizard")).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void guardsFailWithoutInitializingAnyDatabaseConnection(){
        var source=new com.dbcompanion.common.db.SessionDataSource();var service=new com.dbcompanion.service.OntologyWizardService(source,null,null,null,null,json);
        try(var pool=new com.zaxxer.hikari.HikariDataSource();var s=new com.dbcompanion.common.db.PoolSession(pool,"LOW",()->{})){
            s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));
            assertThatThrownBy(()->service.sample(s,"OTHER","T",1,List.of("C"),10,true,Locale.KOREAN)).isInstanceOf(Failure.class);
            assertThatThrownBy(()->service.sample(s,"APP","T",1,List.of("C"),10,false,Locale.KOREAN)).isInstanceOf(Failure.class);
            assertThat(pool.getHikariPoolMXBean()).isNull();
        }
    }
    @Test void repositoryHasNoWritesAndBoundChecksPrecedeSampling() throws Exception {
        String repo=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/OntologyWizardRepository.java"));
        assertThat(repo).contains("verify(snapshot,columns)","setMaxRows(count)","setQueryTimeout(10)","OntologyQueryRepository.verifySampleSource").doesNotContain("INSERT ","UPDATE ","DELETE ","jdbc.execute(","DBMS_RANDOM");
        String guard=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/OntologyQueryRepository.java"));
        assertThat(guard).contains("SYS.ALL_EXTERNAL_TABLES","SYS.ALL_MVIEWS","LocalViewSql.base","SYS.ALL_DEPENDENCIES");
        String service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/OntologyWizardService.java"));
        assertThat(service).contains("\"DRAFT\",new Document", "finally{assistant.finish();}","profile.equals(ai.profile", "samples.verify", "OntologyWizard.merge");
    }
}
