package com.dbcompanion;

import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.MetadataGraphService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MetadataGraphServiceTest {
    final OntologyReadCacheTest db=new OntologyReadCacheTest();final MetadataGraphTest f=new MetadataGraphTest();
    List<Ontology.Entry> entries=f.entries();boolean permission=true;int checks,creates,lists,queries;
    final OntologyRepository catalog=new OntologyRepository(db.jdbc,db.json,new DatabaseRepository(db.jdbc),new TableStructureRepository(db.jdbc)){
        @Override public List<Ontology.Entry> relationshipEntries(String schema,String login){return entries;}
    };
    final MetadataGraphRepository graphs=new MetadataGraphRepository(db.jdbc){
        @Override public MetadataGraph.Access access(MetadataGraph.Plan plan,String login){return new MetadataGraph.Access(true,true,permission,true,List.of());}
        @Override public void verifyProjection(MetadataGraph.Plan plan){checks++;}
        @Override public MetadataGraph.Created create(MetadataGraph.Plan plan){creates++;return new MetadataGraph.Created(plan.name(),List.of(plan.nodeView(),plan.edgeView(),plan.name()),plan.query());}
        @Override public List<MetadataGraphRepository.Existing> existing(String schema){lists++;return List.of(new MetadataGraphRepository.Existing("META","VALID","VALID","VALID",true));}
        @Override public MetadataGraphRepository.ReadResult query(String schema,String name){queries++;return new MetadataGraphRepository.ReadResult("METADATA",List.of(Map.of("EDGE_KIND","CONTAINS")));}
    };
    final MetadataGraphService service=new MetadataGraphService(db.source,catalog,graphs);
    @Test void previewIsReadOnlyAndCreateIsExplicitVersionBoundAndNotRetried(){try(var s=db.session()){
        var p=service.preview(s,"APP","META");assertThat(creates).isZero();assertThat(checks).isEqualTo(1);
        assertThatThrownBy(()->service.create(s,"APP",p.token(),false)).isInstanceOf(Ontology.Failure.class);
        assertThat(service.create(s,"APP",p.token(),true).objects()).hasSize(3);assertThat(creates).isEqualTo(1);assertThat(checks).isEqualTo(2);
        assertThatThrownBy(()->service.create(s,"APP",p.token(),true)).isInstanceOf(Ontology.Failure.class);assertThat(creates).isEqualTo(1);
    }}
    @Test void changedCatalogAndRevokedPermissionPreventAnyDdl(){try(var s=db.session()){
        var p=service.preview(s,"APP","META");entries=List.of(entries.getFirst());
        assertThatThrownBy(()->service.create(s,"APP",p.token(),true)).isInstanceOf(Ontology.Failure.class);assertThat(creates).isZero();
        entries=f.entries();var again=service.preview(s,"APP","META");permission=false;
        assertThatThrownBy(()->service.create(s,"APP",again.token(),true)).isInstanceOf(Ontology.Failure.class);assertThat(creates).isZero();
        assertThat(service.preview(s,"APP","META").canCreate()).isFalse();
    }}
    @Test void ownerAndSchemaMismatchFailBeforeConnecting(){try(var s=db.session()){
        assertThatThrownBy(()->service.preview(s,"OTHER","META")).isInstanceOf(Ontology.Failure.class);
        s.metadata().selectSchema("OTHER");assertThatThrownBy(()->service.preview(s,"OTHER","META")).isInstanceOf(Ontology.Failure.class);assertThat(db.connections).isZero();
    }}
    @Test void existingGraphsAndReadResultsRequireTheLoginOwnedSchema(){try(var s=db.session()){
        assertThat(service.existing(s,"APP")).extracting(MetadataGraphRepository.Existing::name).containsExactly("META");
        assertThat(service.query(s,"APP","META").rows()).hasSize(1);
        assertThat(creates).isZero();assertThat(lists).isEqualTo(1);assertThat(queries).isEqualTo(1);
        assertThatThrownBy(()->service.existing(s,"OTHER")).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->service.query(s,"OTHER","META")).isInstanceOf(Ontology.Failure.class);
        assertThat(lists).isEqualTo(1);assertThat(queries).isEqualTo(1);
    }}
}
