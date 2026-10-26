package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyPlanContextTest {
    final OntologyInquiryTest fixture=new OntologyInquiryTest();
    final JsonMapper json=new JsonMapper();
    @Test void preservesDictionaryRulesAndOmitsUnrelatedSourcesAndLargeCandidateHistory(){
        var data=fixture.data(List.of(fixture.entry("METRIC_A","first","APPROVED",List.of()),fixture.entry("METRIC_B","second","APPROVED",List.of()),fixture.entry("OTHER","unrelated","APPROVED",List.of())));
        var search=OntologyInquiry.search(data,"two metrics","");
        var term=new BusinessGlossary.Term("term",1,"metrics",List.of("alias"),"Aggregate METRIC_A and METRIC_B independently. Never join detail rows.","SELECT COUNT(DISTINCT ID) FROM METRIC_A WHERE FLAG = '1'",true,"now");
        var grounding=new OntologyQuestionGrounding.Result(search.question(),"expanded",List.of(term));
        var candidates=new ArrayList<OntologyRelations.Relation>();
        for(int i=0;i<100;i++)candidates.add(new OntologyRelations.Relation("R"+i,"METRIC_A","APP",i==0?"OTHER":"METRIC_B",List.of("CODE"),List.of("CODE"),"STALE","AI","possible","Do not assume equality",List.of("Uncertainty "+"x".repeat(8000)),null,null));
        String value=OntologyPlanContext.payload(data,search,grounding,candidates,json);
        var result=json.readTree(value);
        assertThat(value.length()).isLessThan(49_000);
        assertThat(value).contains(term.definition(),term.criteria()).doesNotContain("unrelated","updatedAt","aliases","\"review\"");
        assertThat(result.path("availableSources").size()).isEqualTo(2);
        assertThat(result.path("selection").path("omittedSources").asInt()).isEqualTo(1);
        assertThat(result.path("selection").path("omittedCandidates").asInt()+result.path("candidateRelations").size()).isEqualTo(100);
        assertThat(result.path("candidateRelations").get(0).path("status").asText()).isEqualTo("STALE");
        assertThat(result.path("candidateRelations").get(0).path("evidence").get(0).asText()).hasSize(8012);
        assertThat(candidates).hasSize(100);
    }
    @Test void doesNotMatchIdentifierSubstringsOrExportEntireCatalogWithoutMatches(){
        var data=fixture.data(List.of(fixture.entry("METRIC","one","APPROVED",List.of()),fixture.entry("METRIC_A","two","APPROVED",List.of())));
        var search=OntologyInquiry.search(data,"METRIC_A","");
        var result=json.readTree(OntologyPlanContext.payload(data,search,null,List.of(),json));
        assertThat(result.path("availableSources").size()).isEqualTo(1);
        assertThat(result.path("availableSources").get(0).path("name").asText()).isEqualTo("METRIC_A");
        var empty=json.readTree(OntologyPlanContext.payload(data,OntologyInquiry.search(data,"unknown", ""),null,List.of(),json));
        assertThat(empty.path("availableSources").size()).isZero();
    }
}
