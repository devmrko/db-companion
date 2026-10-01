package com.dbcompanion;

import com.dbcompanion.model.AuditHistory.*;
import com.dbcompanion.repository.AuditHistoryRepository;
import com.dbcompanion.service.AuditHistoryService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuditHistoryServiceTest {
    final OntologyReadCacheTest db=new OntologyReadCacheTest();
    final AuditHistoryTest f=new AuditHistoryTest();
    Status state=f.state("READY","READY","READY",false);
    int writes;String owner;
    final AuditHistoryRepository repository=new AuditHistoryRepository(db.jdbc){
        @Override public Status status(String login,String database){owner=login;return state;}
        @Override public void apply(List<String> statements){writes++;}
    };
    final AuditHistoryService service=new AuditHistoryService(db.source,repository);
    @Test void previewIsReadOnlyAndUsesLoginOwnerNotSelectedSchema(){try(var s=db.session()){
        s.metadata().selectSchema("OTHER");
        var p=service.preview(s,Operation.RUN,new Settings(60,0));
        assertThat(owner).isEqualTo(s.metadata().info().username());assertThat(writes).isZero();
        service.apply(s,p);assertThat(writes).isEqualTo(1);
    }}
    @Test void expiryAndChangedPrivilegesBlockWrites(){try(var s=db.session()){
        var p=service.preview(s,Operation.RUN,new Settings(60,0));
        var expired=new Preview(p.token(),p.operation(),p.settings(),p.fingerprint(),Instant.EPOCH,p.statements());
        assertThatThrownBy(()->service.apply(s,expired)).isInstanceOf(IllegalArgumentException.class);
        state=f.state("READY","CONFLICT","READY",false);
        assertThatThrownBy(()->service.apply(s,p)).isInstanceOf(IllegalArgumentException.class);
        assertThat(writes).isZero();
    }}
    @Test void browserOrStoredPreviewSqlIsNeverExecuted(){try(var s=db.session()){
        var p=service.preview(s,Operation.RUN,new Settings(60,0));
        var changed=new Preview(p.token(),p.operation(),p.settings(),p.fingerprint(),p.expires(),List.of("DROP TABLE EXAMPLE"));
        // The service rebuilds the approved fixed operation, not the supplied statement list.
        final List<String> actual=new ArrayList<>();
        var safe=new AuditHistoryService(db.source,new AuditHistoryRepository(db.jdbc){
            @Override public Status status(String login,String database){return state;}
            @Override public void apply(List<String> statements){actual.addAll(statements);}
        });
        safe.apply(s,changed);assertThat(actual).hasSize(1);assertThat(actual.getFirst()).contains("DBMS_SCHEDULER.RUN_JOB").doesNotContain("DROP");
    }}
}
