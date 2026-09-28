package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.MetadataEdit.*;
import com.dbcompanion.repository.MetadataHistoryRepository;
import com.dbcompanion.repository.MetadataRepository;
import com.dbcompanion.service.MetadataService;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic restore tests: no customer database or metadata DDL is contacted. */
class MetadataRestoreServiceTest {
    Value current=new Value(false,null,false); int executes;
    final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){
        case "getAutoCommit" -> true; case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
        case "equals" -> p==a[0]; case "hashCode" -> System.identityHashCode(p); default -> empty(m.getReturnType());});}};
    final JdbcTemplate jdbc=new JdbcTemplate(source);
    final MetadataRepository repository=new MetadataRepository(jdbc){
        @Override public void requireTarget(Target target) { }
        @Override public void requireAnnotationTarget(Target target) { }
        @Override public List<String> columns(Target target) { return List.of("C"); }
        @Override public Value annotation(Target target,String name) { return current; }
        @Override public void execute(String sql) { executes++; assertThat(sql).contains("ADD \"MixedTag\""); }
    };
    final MetadataHistoryRepository history=new MetadataHistoryRepository(jdbc){@Override public void requireHealthyIfTracked(Target target) { }};
    final MetadataService service=new MetadataService(source,repository,null,history);
    private PoolSession session(){return new OntologyReadCacheTest().session();}
    private RestoreRequest request(){return new RestoreRequest("APP","T",null,"annotation","MixedTag","historic");}

    @Test void missingMixedCaseAnnotationRestoresIntoQuotedAddAndSavesWithoutRecasing(){try(var session=session()){
        var form=service.restoreForm(session,request());
        assertThat(form.mode()).isEqualTo("add"); assertThat(form.name()).isEqualTo("\"MixedTag\"");
        assertThat(executes).isZero();
        service.save(session,new SaveRequest("APP","T",null,"annotation",form.mode(),form.name(),form.value(),form.version()));
        assertThat(executes).isEqualTo(1);
    }}

    @Test void restoreLoadsCurrentValueButConcurrentChangeStopsSaveBeforeDdl(){try(var session=session()){
        current=new Value(true,"current",false); var form=service.restoreForm(session,request());
        assertThat(form.currentValue()).isEqualTo("current"); assertThat(form.value()).isEqualTo("historic"); assertThat(executes).isZero();
        current=new Value(true,"changed",false);
        assertThatThrownBy(()->service.save(session,new SaveRequest("APP","T",null,"annotation",form.mode(),form.name(),form.value(),form.version())))
                .isInstanceOf(MetadataEditException.class);
        assertThat(executes).isZero();
    }}
}
