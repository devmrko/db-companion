package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.DatabaseSession;
import com.dbcompanion.model.SemanticContext.*;
import com.dbcompanion.repository.SemanticContextRepository;
import com.dbcompanion.service.SemanticContextService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class SemanticContextServiceTest {
    boolean supported; int writes,lists; boolean matched=true;
    final SessionDataSource source=new SessionDataSource();
    final SemanticContextRepository repository=new SemanticContextRepository(new JdbcTemplate(source)) {
        @Override public Capability capability(){return new Capability("23.26.4.1.0",supported,supported?"supported":"api","API_OWNER");}
        @Override public List<Source> sources(String owner){lists++;return List.of();}
        @Override public void create(String owner,Create input){writes++;}
        @Override public boolean matches(String owner,Create input,tools.jackson.databind.JsonNode expected){return matched;}
    };
    final SemanticContextService service=new SemanticContextService(source,repository);
    PoolSession session(){
        var result=new PoolSession(new HikariDataSource(),"TEST",()->{});
        result.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","TEST","SYNTHETIC"),List.of("APP","OTHER")));
        return result;
    }
    Create input(String schema){return new Create(schema,"TERMS","custom","{\"scope\":\"global\",\"global_callback_func\":\"APP.CONTEXT\"}","",true);}
    @Test void unsupportedDatabaseIsReadOnlyAndForgedPostCannotBypassGate(){try(var session=session()){
        assertThat(service.status(session,"APP").supported()).isFalse();assertThat(lists).isZero();
        assertThatThrownBy(()->service.create(session,input("APP"))).isInstanceOf(RuntimeException.class);assertThat(writes).isZero();
    }}
    @Test void browsingAnotherSchemaDoesNotExposeLoginSourcesOrAllowWrites(){supported=true;try(var session=session()){
        session.metadata().selectSchema("OTHER");assertThat(service.status(session,"OTHER").editable()).isFalse();assertThat(lists).isZero();
        assertThatThrownBy(()->service.create(session,input("OTHER"))).isInstanceOf(RuntimeException.class);assertThat(writes).isZero();
    }}
    @Test void uncertainReadbackIsNotSuccessAndDoesNotRetry(){supported=true;matched=false;try(var session=session()){
        assertThat(service.create(session,input("APP")).verified()).isFalse();assertThat(writes).isEqualTo(1);
    }}
}
