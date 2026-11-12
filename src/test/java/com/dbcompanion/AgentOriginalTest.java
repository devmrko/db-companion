package com.dbcompanion;

import com.dbcompanion.common.AgentDefinitionStore;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.DatabaseSession;
import com.dbcompanion.repository.AgentEngineRepository;
import com.dbcompanion.service.AgentEngineService;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AgentOriginalTest {
    @TempDir Path directory;
    @Test void separatesDatabaseOwnerAndAgentAndRejectsChangedRegistration() throws Exception {
        var json = new JsonMapper();
        var contract = json.readTree("{\"name\":\"FLOW\",\"inputs\":[],\"outputs\":[],\"create_time\":\"first\"}");
        var spec = json.readTree("{\"component_type\":\"Flow\",\"nodes\":[],\"inputs\":[],\"outputs\":[]}");
        AgentDefinitionStore.save(directory.toString(), "DB1", "APP", "FLOW", contract, spec);
        assertThat(AgentDefinitionStore.read(directory.toString(), "DB2", "APP", "FLOW")).isNull();
        assertThat(AgentDefinitionStore.read(directory.toString(), "DB1", "OTHER", "FLOW")).isNull();
        assertThat(AgentDefinitionStore.read(directory.toString(), "DB1", "APP", "OTHER")).isNull();
        var source = new SessionDataSource();
        var repository = new AgentEngineRepository(new JdbcTemplate(source)) {
            @Override public String databaseIdentity() { return "DB1"; }
            @Override public boolean catalogSupported() { return true; }
            @Override public String describe(String name) { return contract.toString(); }
        };
        var service = new AgentEngineService(source, repository);
        ReflectionTestUtils.setField(service, "originalsDirectory", directory.toString());
        try (var session = new PoolSession(new HikariDataSource(), "TEST", () -> {})) {
            session.initialize(new DatabaseSession(new DatabaseInfo("APP", "APP", "TEST", "DB1"), List.of("APP", "OTHER")));
            assertThat(service.definition(session, "APP", "FLOW").path("spec")).isEqualTo(spec);
            assertThatThrownBy(() -> service.definition(session, "OTHER", "FLOW")).isInstanceOf(RuntimeException.class);
            ((tools.jackson.databind.node.ObjectNode)contract).put("create_time", "recreated");
            var error = catchThrowableOfType(com.dbcompanion.common.exception.MetadataEditException.class, () -> service.definition(session, "APP", "FLOW"));
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.userMessage()).contains("DB 등록 정보");
            assertThatThrownBy(() -> service.saveOriginal(session, new AgentEngineService.OriginalInput("APP", "FLOW", spec.toString(), false))).isInstanceOf(RuntimeException.class);
        }
    }
}
