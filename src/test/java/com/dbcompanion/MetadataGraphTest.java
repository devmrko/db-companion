package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.model.OntologyRelations.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MetadataGraphTest {
    final OntologyRelationsTest f=new OntologyRelationsTest();
    Entry entry(String name,String seq){var e=f.entry(name,List.of(f.col("ID","NUMBER"),f.col("AT_DAY","DATE")),List.of(),Map.of());return new Entry(seq,e.revision(),e.documentId(),e.state(),e.actor(),e.recordedAt(),e.document());}
    List<Entry> entries(){return List.of(entry("A_VIEW","11"),entry("B_VIEW","12"));}
    Analysis analyze(List<Entry> entries){return OntologyRelations.analyze("DB","APP",entries,"now");}
    List<Entry> approved(){
        var a=entries().getFirst();var b=entries().getLast();var cols=List.of("ID","AT_DAY");
        var request=new Review("APP","A_VIEW",a.documentId(),1,"B_VIEW",b.documentId(),1,cols,cols,"","two-column relation","business condition only","APPROVED");
        var link=OntologyRelations.review(request,a,b,analyze(entries()),"APP","now");
        return List.of(new Entry("13",2,a.documentId(),"DRAFT","APP","now",OntologyRelations.merge(a,link)),b);
    }
    @Test void keylessViewsStillHaveObjectsColumnsAndContainment(){
        var p=MetadataGraph.build("APP","meta_graph",entries(),analyze(entries()));
        assertThat(p.objects()).isEqualTo(2);assertThat(p.columns()).isEqualTo(4);assertThat(p.relations()).isZero();
        assertThat(p.nodesSql()).contains("DBC_ONTOLOGY_CATALOG","SEQ IN (11,12)","JSON_TABLE");
        assertThat(String.join("\n",p.ddl())).contains("CREATE VIEW","KEY (NODE_ID)","KEY (EDGE_ID)","METADATA_NODE","TRUSTED MODE").doesNotContain("FROM \"APP\".\"A_VIEW\"","OR REPLACE","GRANT ","DROP ","@@");
    }
    @Test void approvedCompositeMappingsRemainOneRelationAndTwoPairs(){
        var rows=approved();var p=MetadataGraph.build("APP","META",rows,analyze(rows));
        assertThat(p.relations()).isEqualTo(1);assertThat(p.mappings()).isEqualTo(2);assertThat(p.excluded()).isEmpty();
        assertThat(p.edgesSql()).contains("c.SEQ=13 AND l.id=", "a.pos=b.pos", "mapping_count", "condition_text");
        assertThat(p.query()).contains("RELATION_ID","MAPPING_POSITION","MAPPING_COUNT","CONDITION_TEXT");
    }
    @Test void rejectedCandidatesAndStaleApprovalsAreNeverPublished(){
        var rows=approved();var a=rows.getFirst();var doc=a.document();var m=doc.meaning();
        var changed=new Document(1,doc.source(),new Meaning("changed",m.description(),m.columns(),m.relations()),doc.origin(),doc.profile(),doc.analysis(),doc.links());
        var stale=List.of(new Entry("14",3,a.documentId(),"DRAFT","APP","now",changed),rows.getLast());
        var p=MetadataGraph.build("APP","META",stale,analyze(stale));assertThat(p.relations()).isZero();assertThat(p.excluded()).hasSize(1);assertThat(p.edgesSql()).contains("l.status='APPROVED' AND (1=0)");
        var candidate=new Link(doc.links().getFirst().id(),rows.getLast().documentId(),2,1,"APP","B_VIEW",List.of("ID","AT_DAY"),List.of("ID","AT_DAY"),"candidate","","CANDIDATE","RDF",List.of(),"APP","now");
        var candidateDoc=new Document(1,doc.source(),doc.meaning(),doc.origin(),null,null,List.of(candidate));
        var unreviewed=List.of(new Entry("15",2,a.documentId(),"DRAFT","APP","now",candidateDoc),rows.getLast());
        assertThat(MetadataGraph.build("APP","META",unreviewed,analyze(unreviewed)).relations()).isZero();
    }
    @Test void sequenceNamesAndCrossSchemaInputsCannotInjectDdl(){
        var rows=entries();var a=rows.getFirst();
        assertThatThrownBy(()->MetadataGraph.build("APP","G;DROP",rows,analyze(rows))).isInstanceOf(Failure.class);
        var bad=List.of(new Entry("1) OR 1=1",1,a.documentId(),a.state(),a.actor(),a.recordedAt(),a.document()),rows.getLast());
        assertThatThrownBy(()->MetadataGraph.build("APP","META",bad,analyze(bad))).isInstanceOf(Failure.class);
        assertThatThrownBy(()->MetadataGraph.build("OTHER","META",rows,analyze(rows))).isInstanceOf(Failure.class);
        assertThatThrownBy(()->MetadataGraph.build("APP","META",List.of(),new Analysis("APP",List.of(),List.of(),"now"))).isInstanceOf(Failure.class);
    }
    @Test void draftRequiresOwnerTokenConsentExpiryAndSingleUse(){
        var p=MetadataGraph.build("APP","META",entries(),analyze(entries()));var state=new MetadataGraph.State();var now=Instant.now();
        var access=new MetadataGraph.Access(true,true,true,true,List.of());var preview=new MetadataGraph.Preview("TOKEN",p,access,now.plusSeconds(60),true);
        state.prepare(new MetadataGraph.Draft(preview,entries()));
        assertThatThrownBy(()->state.consume("APP","TOKEN",false,now)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->state.consume("OTHER","TOKEN",true,now)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->state.consume("APP","TOKEN",true,now.plusSeconds(60))).isInstanceOf(Failure.class);
        assertThat(state.consume("APP","TOKEN",true,now).preview()).isEqualTo(preview);
        assertThatThrownBy(()->state.consume("APP","TOKEN",true,now)).isInstanceOf(Failure.class);
        assertThat(new MetadataGraph.Access(true,true,false,true,List.of()).allowed()).isFalse();
        assertThat(new MetadataGraph.Access(true,true,true,true,List.of("existing view")).allowed()).isFalse();
    }
}
