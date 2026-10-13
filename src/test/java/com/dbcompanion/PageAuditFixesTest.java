package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.service.*;
import com.dbcompanion.repository.AiSqlCandidates;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PageAuditFixesTest {
    private final JsonMapper json=new JsonMapper();
    @Test void rdfRetrievalQuestionDoesNotStopSearchForClarification(){
        var value=OntologyAssistantReply.ontologyQuestion("{\"searchQuestion\":\"Which sources describe standard and business activity?\",\"searchTerms\":[\"standard activity\",\"business activity\"],\"clarifications\":[]}",json);
        assertThat(value.questions()).isEmpty();assertThat(value.concepts()).hasSize(2);
        var missing=OntologyAssistantReply.ontologyQuestion("{\"searchQuestion\":\"activity metadata\",\"searchTerms\":[\"activity\"],\"clarifications\":[{\"missingField\":\"DATE_RANGE\",\"question\":\"Which day?\"}]}",json);
        assertThat(missing.questions()).containsExactly("Which day?");
        assertThatThrownBy(()->OntologyAssistantReply.ontologyQuestion("{\"searchQuestion\":\"activity metadata\",\"searchTerms\":[\"activity\"],\"clarifications\":[{\"missingField\":\"SOURCE\",\"question\":\"Which source?\"}]}",json)).isInstanceOf(RuntimeException.class);
    }
    @Test void dictionaryRdfKeepsBusinessMeaningSeparateFromQualifiedMappings() throws Exception {
        var mapping=new GlossaryRdf.Definition("Sales","currency","period","transaction",List.of("exclude test users"),List.of("specification revision 1"),List.of(new GlossaryRdf.Mapping("APP","SALES","AMOUNT","VALUE","SUM"),new GlossaryRdf.Mapping("APP","REFUNDS","AMOUNT","VALUE","SUM")));
        var term=new BusinessGlossary.Term("11111111-1111-1111-1111-111111111111",2,"Revenue",List.of("Payment amount"),"Payment transactions","reference only",true,"now",mapping);
        var view=BusinessGlossaryRdf.export(term);
        assertThat(view.meaning()).contains("prefLabel","definition","ruleNote","revision").doesNotContain("/column/AMOUNT");
        assertThat(view.mapping()).contains("/SALES/column/AMOUNT","/REFUNDS/column/AMOUNT","USER_DECLARED_NOT_JOIN_APPROVAL");
        assertThat(view.sources()).hasSize(2);assertThat(json.readValue(json.writeValueAsString(term),BusinessGlossary.Term.class)).isEqualTo(term);
        var graph=org.eclipse.rdf4j.rio.Rio.parse(new java.io.StringReader(view.meaning()+view.mapping()),"urn:test:",org.eclipse.rdf4j.rio.RDFFormat.TURTLE);
        assertThat(graph).hasSize(view.triples().size());
    }
    @Test void v1ImportPreservesExistingRdfAndV2CarriesReviewedMappings(){
        var rdf=new GlossaryRdf.Definition("Sales","currency","","",List.of(),List.of(),List.of());
        var previous=new BusinessGlossary.Term("11111111-1111-1111-1111-111111111111",1,"Revenue",List.of(),"old","",true,"now",rdf);
        var value=new BusinessGlossary.Draft("Revenue",List.of(),"new","",true);
        var preview=GlossaryTransfer.preview("APP",new GlossaryTransfer.Document(GlossaryTransfer.FORMAT,1,List.of(value)),List.of(previous));
        assertThat(preview.entries().getFirst().value().rdf()).isEqualTo(rdf);
        assertThat(new GlossaryTransfer.Document(GlossaryTransfer.FORMAT,2,List.of(value.withRdf(rdf))).terms().getFirst().rdf()).isEqualTo(rdf);
        assertThatThrownBy(()->new GlossaryTransfer.Document(GlossaryTransfer.FORMAT,1,List.of(value.withRdf(rdf)))).isInstanceOf(RuntimeException.class);
    }
    @Test void sqlExplanationReportsActualSourceColumnReferencesButDoesNotCertifyRules(){
        var rdf=new GlossaryRdf.Definition("Sales","currency","period","transaction",List.of("use requested period, never lifetime snapshot"),List.of(),List.of(new GlossaryRdf.Mapping("APP","SALES","AMOUNT","VALUE","SUM")));
        var term=new BusinessGlossary.Term("11111111-1111-1111-1111-111111111111",1,"Revenue",List.of(),"sum","",true,"now",rdf);
        assertThat(GlossarySqlExplanation.analyze("SELECT SUM(s.AMOUNT) FROM APP.SALES s",term,"APP").references().getFirst().status()).isEqualTo("REFERENCED");
        assertThat(GlossarySqlExplanation.analyze("SELECT SUM(s.ACM_AMOUNT) FROM APP.SALES s",term,"APP").references().getFirst().status()).isEqualTo("COLUMN_NOT_REFERENCED");
        assertThat(GlossarySqlExplanation.analyze("SELECT SUM(s.ACM_AMOUNT) FROM APP.USER_SNAPSHOT s",term,"APP").references().getFirst().status()).isEqualTo("SOURCE_NOT_REFERENCED");
        assertThat(GlossarySqlExplanation.analyze("SELECT SUM(AMOUNT) FROM APP.SALES",term,"APP").references().getFirst().status()).isEqualTo("UNCONFIRMED");
        assertThat(GlossarySqlExplanation.analyze("DELETE FROM APP.SALES",term,"APP").status()).isEqualTo("UNSUPPORTED");
    }
    @Test void internalTextAndVectorQueriesCannotBecomeBusinessCandidates(){
        for(String sql:List.of("SELECT * FROM APP.DR$SEARCH$I","SELECT * FROM CTXSYS.DRV$DELETE","SELECT * FROM APP.VECTOR$INDEX","SELECT * FROM APP.DATA#RDF","SELECT * FROM APP.DATA$VECTAB"))assertThat(AiSqlCandidates.businessObjects(sql)).isEmpty();
        assertThat(AiSqlCandidates.businessObjects("SELECT * FROM APP.SALES")).hasSize(1);
    }
    @Test void readOnlyAuditHelpDoesNotContainInstallationOrFixedOneDayWindow(){
        var sections=com.dbcompanion.common.db.AuditHistorySql.helpSections("SYS.UNIFIED_AUDIT_TRAIL");
        assertThat(sections.get("READ")).contains(":from_date",":to_date",":db_user",":context").doesNotContain("CREATE","ENABLE(","SYSTIMESTAMP");
        assertThat(sections.get("INSTALL")).contains("DDL commits","CREATE","ENABLE(");
    }
}
