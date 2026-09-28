package com.dbcompanion;

import com.dbcompanion.model.ColumnInfo;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.service.OntologyGovernanceService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.groups.Tuple.tuple;

class OntologyGovernanceTest {
    private Snapshot snapshot(List<ColumnInfo> columns) { return new Snapshot("DB","APP","ORDERS","orders",columns,List.of(),"now"); }
    @Test void baselineRebasePreservesOnlyDefinitionsAndValueMappingsForStillPresentColumns() {
        var before=snapshot(List.of(new ColumnInfo(1,"TOTAL","NUMBER","N","total"),new ColumnInfo(2,"LEGACY","VARCHAR2(10)","Y","legacy")));
        var meaning=Ontology.initial(before);
        var columns=new java.util.LinkedHashMap<>(meaning.columns());columns.put("TOTAL",new ColumnMeaning("approved total","NON_SENSITIVE"));
        var old=new Meaning("Order","definition",columns,meaning.relations(),List.of());
        var current=snapshot(List.of(new ColumnInfo(1,"TOTAL","NUMBER","N","new total"),new ColumnInfo(2,"STATUS","VARCHAR2(10)","Y","status")));
        var rebased=OntologyGovernanceService.rebase(old,current);
        assertThat(rebased.columns()).containsOnlyKeys("TOTAL","STATUS");
        assertThat(rebased.columns().get("TOTAL").description()).isEqualTo("approved total");
        assertThat(rebased.columns().get("STATUS").description()).isEqualTo("status");
    }
    @Test void snapshotAcceptanceCannotKeepInvalidValueMappingsAfterColumnRemoval() {
        var before=snapshot(List.of(new ColumnInfo(1,"CODE","VARCHAR2(10)","N","code")));
        var mapping=new com.dbcompanion.model.OntologyValues.Binding("9b6d440d-b960-4bfc-9a4a-430655274cd3","CODE","TEXT","A","Active",List.of("enabled"),"");
        var old=new Meaning("Status","",Ontology.initial(before).columns(),java.util.Map.of(),List.of(mapping));
        var current=snapshot(List.of(new ColumnInfo(1,"OTHER","VARCHAR2(10)","N","other")));
        assertThat(OntologyGovernanceService.rebase(old,current).valueMappings()).isEmpty();
    }
    private Entry entry(Snapshot source){return new Entry("1",1,"11111111-1111-1111-1111-111111111111","APPROVED","ACTOR","now",new Document(1,source,Ontology.initial(source),"USER",null));}
    @Test void annotationDriftComparesMixedCaseIdentityEmptyValueAndInheritedState() {
        var columns=List.of(new ColumnInfo(1,"C","VARCHAR2(10)","Y",""));
        var before=new Snapshot("DB","APP","ORDERS","",columns,List.of(),"then",List.of(new Ontology.Annotation("C","MixedName",null,null,null)),"SUCCESS");
        var now=new Snapshot("DB","APP","ORDERS","",columns,List.of(),"now",List.of(new Ontology.Annotation("C","MixedName","",null,null),new Ontology.Annotation(null,"SCOPE","x","DOM","D")),"SUCCESS");
        var drift=OntologyGovernanceService.compare(entry(before),now);
        assertThat(drift.status()).isEqualTo("REVIEW_REQUIRED");assertThat(drift.differences()).extracting("kind","name").containsExactlyInAnyOrder(tuple("ANNOTATION","C\u0000MixedName"),tuple("ANNOTATION","\u0000SCOPE"));
    }
    @Test void historicalSnapshotWithoutAnnotationFieldsIsNotReportedUnchanged() {
        var old=snapshot(List.of(new ColumnInfo(1,"C","NUMBER","N",null)));
        var now=new Snapshot("DB","APP","ORDERS","orders",old.columns(),List.of(),"now",List.of(),"UNCONFIRMED");
        assertThat(OntologyGovernanceService.compare(entry(old),now).status()).isEqualTo("ANNOTATIONS_UNCONFIRMED");
    }
}
