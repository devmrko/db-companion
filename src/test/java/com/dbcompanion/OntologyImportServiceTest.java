package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.OntologyImportService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyImportServiceTest {
    Entry saved;
    Snapshot current=snapshot("original",List.of(column("ID")),"SUCCESS");
    int writes,requires;
    final SessionDataSource source=new SessionDataSource(){
        @Override public Connection getConnection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
            case "getAutoCommit" -> true;case "getNetworkTimeout" -> 40_000;
            case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
            case "equals" -> p==a[0];case "hashCode" -> System.identityHashCode(p);
            default -> m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
        });}
    };
    final JdbcTemplate jdbc=new JdbcTemplate(source);
    final JsonMapper json=new JsonMapper();
    final OntologyRepository repository=new OntologyRepository(jdbc,json,new DatabaseRepository(jdbc),new TableStructureRepository(jdbc)){
        @Override public void require(String schema,String login){requires++;}
        @Override public Entry entry(String schema,String table,int revision){return saved;}
        @Override public Snapshot snapshot(String database,String schema,String table){return current;}
        @Override public Entry append(String schema,String table,int revision,String state,Document doc){
            assertThat(revision).isEqualTo(saved==null?0:saved.revision());writes++;
            saved=new Entry(""+writes,revision+1,"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",state,"ACTOR","now",doc);return saved;
        }
    };
    final OntologyImportService service=new OntologyImportService(source,repository,json);
    static ColumnInfo column(String name){return new ColumnInfo(1,name,"NUMBER","Y","DB comment");}
    static Snapshot snapshot(String comment,List<ColumnInfo> columns,String status){return new Snapshot("DB","APP","BUSINESS_VIEW",comment,columns,List.of(),"now",List.of(),status);}
    static PoolSession session(){var s=new PoolSession(new HikariDataSource(),"LOW",()->{});s.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));return s;}
    void baseline(){saved=new Entry("1",3,"document","APPROVED","EDITOR","before",new Document(1,current,new Meaning("Business concept","Hand-written definition",Map.of("ID",new ColumnMeaning("Human definition","SENSITIVE")),Map.of()),"USER","ORIGINAL_PROFILE"));}
    @Test void newViewNeedsPreviewAndConfirmationThenCreatesDraft(){try(var s=session()){
        var p=service.preview(s,"APP","BUSINESS_VIEW");assertThat(p.outcome()).isEqualTo("NEW");assertThat(writes).isZero();
        assertThatThrownBy(()->service.apply(s,"APP","BUSINESS_VIEW",p.token(),false)).isInstanceOf(Failure.class);
        var result=service.apply(s,"APP","BUSINESS_VIEW",p.token(),true);
        assertThat(result.revision()).isEqualTo(1);assertThat(result.triples()).isPositive();assertThat(saved.state()).isEqualTo("DRAFT");assertThat(writes).isEqualTo(1);
    }}
    @Test void unchangedSnapshotCanBeExplicitlyRecapturedWithoutLosingDefinitions(){baseline();var old=saved;try(var s=session()){
        current=new Snapshot(current.database(),current.schema(),current.table(),current.comment(),current.columns(),current.keys(),"later",current.annotations(),current.annotationStatus());
        var p=service.preview(s,"APP","BUSINESS_VIEW");assertThat(p.outcome()).isEqualTo("UNCHANGED");assertThat(p.token()).isNotBlank();assertThat(writes).isZero();
        assertThatThrownBy(()->service.apply(s,"APP","BUSINESS_VIEW",p.token(),false)).isInstanceOf(Failure.class);assertThat(writes).isZero();
        var result=service.apply(s,"APP","BUSINESS_VIEW",p.token(),true);
        assertThat(result.revision()).isEqualTo(4);assertThat(result.triples()).isPositive();assertThat(writes).isEqualTo(1);
        assertThat(saved.document().source().capturedAt()).isEqualTo("later");assertThat(saved.document().meaning()).isEqualTo(old.document().meaning());
        assertThat(saved.document().links()).isEqualTo(old.document().links());assertThat(saved.state()).isEqualTo("DRAFT");assertThat(old.state()).isEqualTo("APPROVED");
        assertThatThrownBy(()->service.apply(s,"APP","BUSINESS_VIEW",p.token(),true)).isInstanceOf(Failure.class);assertThat(writes).isEqualTo(1);
    }}
    @Test void unchangedPreviewStillRejectsAChangedDatabaseSnapshot(){baseline();try(var s=session()){
        var p=service.preview(s,"APP","BUSINESS_VIEW");current=snapshot("changed after comparison",List.of(column("ID")),"SUCCESS");
        assertThatThrownBy(()->service.apply(s,"APP","BUSINESS_VIEW",p.token(),true)).isInstanceOf(Failure.class);assertThat(writes).isZero();
    }}
    @Test void changedMetadataPreservesHumanMeaningAndPriorRevision(){baseline();var old=saved;current=snapshot("new DB comment",List.of(column("ID"),column("CODE")),"SUCCESS");try(var s=session()){
        var p=service.preview(s,"APP","BUSINESS_VIEW");assertThat(p.outcome()).isEqualTo("CHANGED");assertThat(p.differences()).hasSize(2);assertThat(saved).isSameAs(old);
        service.apply(s,"APP","BUSINESS_VIEW",p.token(),true);
        assertThat(saved.revision()).isEqualTo(4);assertThat(saved.state()).isEqualTo("DRAFT");assertThat(saved.document().source()).isEqualTo(current);
        assertThat(saved.document().meaning().description()).isEqualTo("Hand-written definition");assertThat(saved.document().meaning().columns().get("ID")).isEqualTo(old.document().meaning().columns().get("ID"));
        assertThat(saved.document().meaning().columns().get("CODE").description()).isEqualTo("DB comment");assertThat(saved.document().profile()).isEqualTo("ORIGINAL_PROFILE");assertThat(old.revision()).isEqualTo(3);
    }}
    @Test void removedColumnsAreNotSilentlyDiscarded(){baseline();current=snapshot("new",List.of(column("NEW_ID")),"SUCCESS");try(var s=session()){
        var p=service.preview(s,"APP","BUSINESS_VIEW");assertThat(p.outcome()).isEqualTo("REVIEW");assertThat(p.reason()).isEqualTo("removed");assertThat(p.token()).isEmpty();assertThat(writes).isZero();
    }}
    @Test void removedForeignKeyDefinitionRequiresReview(){
        var key=new Key("FK_PARENT","R",List.of("ID"),"APP","PARENT",List.of("ID"),"ENABLED","VALIDATED");
        var old=new Snapshot("DB","APP","BUSINESS_VIEW","original",List.of(column("ID")),List.of(key),"before",List.of(),"SUCCESS");
        saved=new Entry("1",1,"doc","DRAFT","EDITOR","before",new Document(1,old,new Meaning("","",Map.of("ID",new ColumnMeaning("","UNKNOWN")),Map.of("FK_PARENT","Human relationship")),"USER",null));
        try(var s=session()){assertThat(service.preview(s,"APP","BUSINESS_VIEW").reason()).isEqualTo("removed");assertThat(writes).isZero();}
    }
    @Test void incompleteAnnotationReadIsNotADeletionOrSafeRefresh(){baseline();current=snapshot("original",List.of(column("ID")),"UNCONFIRMED");try(var s=session()){
        var p=service.preview(s,"APP","BUSINESS_VIEW");assertThat(p.outcome()).isEqualTo("REVIEW");assertThat(p.reason()).isEqualTo("annotations");assertThat(writes).isZero();
    }}
    @Test void legacyAnnotationBaselineCanBeCapturedExplicitly(){current=snapshot("original",List.of(column("ID")),"NOT_REQUESTED");baseline();current=snapshot("original",List.of(column("ID")),"SUCCESS");try(var s=session()){
        var p=service.preview(s,"APP","BUSINESS_VIEW");assertThat(p.outcome()).isEqualTo("CHANGED");assertThat(p.differences()).anyMatch(d->d.kind().equals("ANNOTATION_STATUS"));
    }}
    @Test void changesAfterPreviewCannotBeApplied(){baseline();current=snapshot("changed",List.of(column("ID")),"SUCCESS");try(var s=session()){
        var p=service.preview(s,"APP","BUSINESS_VIEW");current=snapshot("changed again",List.of(column("ID")),"SUCCESS");
        assertThatThrownBy(()->service.apply(s,"APP","BUSINESS_VIEW",p.token(),true)).isInstanceOf(Failure.class);assertThat(writes).isZero();
    }}
    @Test void concurrentRevisionAndReplayedTokensAreRejected(){baseline();current=snapshot("changed",List.of(column("ID")),"SUCCESS");try(var s=session()){
        var p=service.preview(s,"APP","BUSINESS_VIEW");saved=new Entry("2",4,"document","DRAFT","OTHER","later",saved.document());
        assertThatThrownBy(()->service.apply(s,"APP","BUSINESS_VIEW",p.token(),true)).isInstanceOf(Failure.class);
        var fresh=service.preview(s,"APP","BUSINESS_VIEW");service.apply(s,"APP","BUSINESS_VIEW",fresh.token(),true);
        assertThatThrownBy(()->service.apply(s,"APP","BUSINESS_VIEW",fresh.token(),true)).isInstanceOf(Failure.class);assertThat(writes).isEqualTo(1);
    }}
    @Test void tokensAreBoundToSessionSchemaAndObject(){try(var a=session();var b=session()){
        var p=service.preview(a,"APP","BUSINESS_VIEW");
        assertThatThrownBy(()->service.apply(b,"APP","BUSINESS_VIEW",p.token(),true)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->service.apply(a,"APP","OTHER_TABLE",p.token(),true)).isInstanceOf(Failure.class);
        assertThatThrownBy(()->service.preview(a,"OTHER","BUSINESS_VIEW")).isInstanceOf(Failure.class);
        assertThatThrownBy(()->service.preview(a,"APP",OntologySql.TABLE)).isInstanceOf(Failure.class);assertThat(writes).isZero();
    }}
    @Test void refreshingPreviewInvalidatesEarlierToken(){try(var s=session()){
        var first=service.preview(s,"APP","BUSINESS_VIEW");var second=service.preview(s,"APP","BUSINESS_VIEW");
        assertThatThrownBy(()->service.apply(s,"APP","BUSINESS_VIEW",first.token(),true)).isInstanceOf(Failure.class);
        service.apply(s,"APP","BUSINESS_VIEW",second.token(),true);assertThat(requires).isEqualTo(3);
    }}
}
