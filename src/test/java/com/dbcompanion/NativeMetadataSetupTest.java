package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.NativeMetadata.*;
import com.dbcompanion.repository.NativeMetadataRepository;
import com.dbcompanion.service.NativeMetadataService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativeMetadataSetupTest {
    final OntologyReadCacheTest db=new OntologyReadCacheTest();
    String storage="READY",java="MISSING";
    int enables,installs,snapshotReads;
    boolean missingRdf;
    final NativeMetadataRepository rdf=new NativeMetadataRepository(db.jdbc,db.json){
        @Override public String storage(){return storage;}
        @Override public String javaState(){return java;}
        @Override public boolean api(){return true;}
        @Override public boolean javaSetupAvailable(){return true;}
        @Override public void enableJava(){enables++;}
        @Override public void install(){requireJava();assertThat(db.networkTimeout).isEqualTo(130_000);installs++;storage="READY";}
        @Override public List<Snapshot> snapshots(){snapshotReads++;if(missingRdf)throw new org.springframework.jdbc.UncategorizedSQLException("RDF","",new java.sql.SQLException("Missing RDF class","",29540));return List.of();}
    };
    final NativeMetadataService service=new NativeMetadataService(db.source,rdf,null);
    PoolSession session(String user){
        var s=new PoolSession(new HikariDataSource(),"LOW",()->{});
        s.initialize(new DatabaseSession(new DatabaseInfo(user,user,"LOW","DB"),List.of(user)));return s;
    }
    @Test void missingJavaDoesNotHideExistingStorageOrExecuteSemMatch(){try(var s=session("ADMIN")){
        var value=service.status(s,"ADMIN");
        assertThat(value.storage()).isEqualTo("READY");assertThat(value.runtime().state()).isEqualTo("MISSING");
        assertThat(value.runtime().canEnable()).isTrue();assertThat(value.runtime().sql()).contains("'JAVAVM'");
        assertThat(snapshotReads).isZero();assertThat(enables).isZero();assertThat(db.networkTimeout).isEqualTo(15_000);
    }}
    @Test void validJavaWithMissingRdfClassKeepsStorageStatusAndNeverReenablesJava(){try(var s=session("ADMIN")){
        java="READY";missingRdf=true;var status=service.status(s,"ADMIN");
        assertThat(status.storage()).isEqualTo("READY");assertThat(status.runtime().state()).isEqualTo("RDF_UNAVAILABLE");assertThat(status.runtime().canEnable()).isFalse();assertThat(status.snapshots()).isEmpty();assertThat(enables).isZero();
        assertThat(NativeMetadataService.missingRdfClass(new java.sql.SQLException("Other error","",1031))).isFalse();
    }}
    @Test void setupNeedsExplicitConsentAndAdminBeforeAnyDatabaseWork(){try(var admin=session("ADMIN");var app=session("APP")){
        assertThatThrownBy(()->service.enableJava(admin,"ADMIN",false)).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->service.enableJava(app,"APP",true)).isInstanceOf(Ontology.Failure.class);
        assertThat(db.connections).isZero();assertThat(enables).isZero();
        assertThat(service.status(app,"APP").runtime().canEnable()).isFalse();
    }}
    @Test void requestDoesNotClaimInstallationOrRestartAndCannotBeRepeatedInSession(){try(var s=session("ADMIN")){
        var pending=service.enableJava(s,"ADMIN",true);
        assertThat(enables).isEqualTo(1);assertThat(pending.runtime().state()).isEqualTo("RESTART_REQUIRED");
        assertThat(pending.runtime().canEnable()).isFalse();assertThat(snapshotReads).isZero();
        assertThatThrownBy(()->service.enableJava(s,"ADMIN",true)).isInstanceOf(Ontology.Failure.class);
        assertThat(enables).isEqualTo(1);
        java="LOADING";assertThat(service.status(s,"ADMIN").runtime().state()).isEqualTo("LOADING");
        java="READY";assertThat(service.status(s,"ADMIN").runtime().state()).isEqualTo("READY");
        assertThat(snapshotReads).isEqualTo(1);assertThat(db.networkTimeout).isEqualTo(15_000);
    }}
    @Test void missingPrerequisitePreventsDdlAndTimeoutIsRestoredOnFailureAndSuccess(){try(var s=session("ADMIN")){
        storage="MISSING";
        assertThatThrownBy(()->service.install(s,"ADMIN",true)).isInstanceOf(Ontology.Failure.class);
        assertThat(installs).isZero();assertThat(db.networkTimeout).isEqualTo(15_000);
        java="READY";assertThat(service.install(s,"ADMIN",true).storage()).isEqualTo("READY");
        assertThat(installs).isEqualTo(1);assertThat(db.networkTimeout).isEqualTo(15_000);
    }}
}
