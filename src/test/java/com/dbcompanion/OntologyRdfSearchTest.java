package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OntologyRdfSearchTest {
    @Test void dictionarySourcesWinBeforeTenHitLimitEvenWhenCommonWordsMatchManyTables(){
        var entries=new ArrayList<com.dbcompanion.model.Ontology.Entry>();
        for(int i=0;i<15;i++)entries.add(fixture.entry("DECOY_"+i,"common date","DRAFT",List.of()));
        entries.add(fixture.entry("METRIC_A","specific metric","DRAFT",List.of()));entries.add(fixture.entry("METRIC_B","another metric","DRAFT",List.of()));
        var result=OntologyRdfSearch.search(fixture.data(entries),"metrics",List.of("common"),"",Set.of("METRIC_A","METRIC_B"));
        assertThat(result.hits().subList(0,2)).extracting(OntologyRdfSearch.Hit::table).containsExactly("METRIC_A","METRIC_B");
        assertThat(result.hits()).hasSize(10);assertThat(result.limited()).isTrue();
    }
    final OntologyInquiryTest fixture=new OntologyInquiryTest();
    @Test void draftMetricsAreRetrievedAsRdfWithoutAnyJoinOrCandidateId(){
        var data=fixture.data(List.of(fixture.entry("STANDARD_USERS","standard activity","DRAFT",List.of()),fixture.entry("BUSINESS_USERS","business activity","DRAFT",List.of()),fixture.entry("UNRELATED","other","APPROVED",List.of())));
        var result=OntologyRdfSearch.search(data,"Find source mappings and counting rules for two activity metrics",List.of("STANDARD_USERS","BUSINESS_USERS"),"");
        assertThat(result.hits()).extracting(OntologyRdfSearch.Hit::table).containsExactlyInAnyOrder("STANDARD_USERS","BUSINESS_USERS");
        assertThat(result.hits()).allMatch(h->h.state().equals("DRAFT")&&!h.triples().isEmpty()&&h.versionIri().contains("/revision/1"));
        assertThat(result.hits().stream().filter(h->h.table().equals("STANDARD_USERS")).findFirst().orElseThrow().triples()).anyMatch(t->t.predicate().endsWith("businessConcept")&&t.object().value().equals("standard activity"));
        assertThat(result.hits().stream().flatMap(h->h.triples().stream())).noneMatch(t->t.subject().contains("/column/EMAIL")||t.object().value().contains("/column/EMAIL"));
    }
    @Test void noMatchesAreDifferentFromNoPathsAndAnchorLimitsRetrieval(){
        var data=fixture.data(List.of(fixture.entry("A","standard","DRAFT",List.of()),fixture.entry("B","business","DRAFT",List.of())));
        assertThat(OntologyRdfSearch.search(data,"missing",List.of("unregistered term"),"").hits()).isEmpty();
        assertThat(OntologyRdfSearch.search(data,"both",List.of("standard","business"),"B").hits()).extracting(OntologyRdfSearch.Hit::table).containsExactly("B");
        assertThatThrownBy(()->OntologyRdfSearch.search(data,"q",List.of(),"")).isInstanceOf(Ontology.Failure.class);
    }
}
