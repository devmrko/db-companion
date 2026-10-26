package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyStatisticsTest {
    ColumnInfo column(String name,String type){return new ColumnInfo(1,name,type,"Y","");}
    @Test void observedFrequenciesPreserveSpacesAndNullsWithoutClaimingPopulation(){
        var report=OntologyStatistics.summarize(List.of(column("FLAG","VARCHAR2")),Arrays.asList(List.of("1"),List.of("1"),List.of(" 1"),Arrays.asList((String)null)),"now");
        var c=report.columns().getFirst();assertThat(c.observed()).isEqualTo(4);assertThat(c.nulls()).isEqualTo(1);assertThat(c.distinctObserved()).isEqualTo(2);
        assertThat(c.topValues()).containsExactly(new OntologyStatistics.Frequency("1",2),new OntologyStatistics.Frequency(" 1",1));
        assertThat(report.method()).contains("NOT_RANDOM","NOT_WHOLE_TABLE");
        assertThat(c.minimum()).isNull();assertThat(c.maximum()).isNull();
    }
    @Test void numericAndDateRangesUseValueOrdering(){
        var report=OntologyStatistics.summarize(List.of(column("N","NUMBER"),column("D","DATE")),List.of(List.of("10","2026-08-03T00:00:00"),List.of("2","2026-07-03T00:00:00"),List.of("-3","2026-09-03T00:00:00")),"now");
        assertThat(report.columns().getFirst().minimum()).isEqualTo("-3");assertThat(report.columns().getFirst().maximum()).isEqualTo("10");
        assertThat(report.columns().get(1).minimum()).startsWith("2026-07");assertThat(report.columns().get(1).maximum()).startsWith("2026-09");
    }
    @Test void privateColumnValuesAreSuppressedAndLargerSampleValuesNeverEnterAi(){
        var report=OntologyStatistics.summarize(List.of(column("CODE","VARCHAR2")),List.of(List.of("PUBLIC"),List.of("person@example.test")),"now");
        var c=report.columns().getFirst();assertThat(c.withheld()).isEqualTo(2);assertThat(c.topValues()).isEmpty();assertThat(c.distinctObserved()).isZero();
        var ordinary=OntologyStatistics.summarize(List.of(column("CODE","VARCHAR2")),List.of(List.of("LARGE_SAMPLE_ONLY")),"now");
        assertThat(new JsonMapper().writeValueAsString(OntologyStatistics.aiEvidence(ordinary))).contains("topFrequencies").doesNotContain("LARGE_SAMPLE_ONLY");
    }
    @Test void truncatedValuesAreNotMergedIntoFalseFrequencyBucketsAndLimitsAreEnforced(){
        var report=OntologyStatistics.summarize(List.of(column("CODE","VARCHAR2")),List.of(List.of("x".repeat(201)),List.of("x".repeat(200)+"y")),"now");
        assertThat(report.columns().getFirst().withheld()).isEqualTo(2);assertThat(report.columns().getFirst().topValues()).isEmpty();
        assertThatThrownBy(()->OntologyStatistics.summarize(List.of(column("N","NUMBER")),Collections.nCopies(1001,List.of("1")),"now")).isInstanceOf(Failure.class);
    }
    @Test void consentAndSchemaAreCheckedBeforeAnyConnection(){try(var session=new OntologyReadCacheTest().session()){
        var service=new OntologyValuesService(new com.dbcompanion.common.db.SessionDataSource(),null,null);
        assertThatThrownBy(()->service.statistics(session,new OntologyStatistics.Request("OTHER","T",1,"C",true))).isInstanceOf(Failure.class);
        assertThatThrownBy(()->service.statistics(session,new OntologyStatistics.Request("APP","T",1,"C",false))).isInstanceOf(Failure.class);
    }}
}
