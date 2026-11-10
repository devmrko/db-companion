package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.AgentEngine;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.DatabaseSession;
import com.dbcompanion.repository.AgentEngineRepository;
import com.dbcompanion.service.AgentEngineService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class AgentEngineTest {
    static final String SPEC = "{\"component_type\":\"Flow\",\"agentspec_version\":\"25.4.2\",\"inputs\":[{\"title\":\"quantity\",\"type\":\"number\"}]}";
    int executions;
    boolean supported = true;
    boolean fails;
    String detail = "{\"name\":\"FLOW_A\",\"inputs\":[{\"title\":\"quantity\",\"type\":\"number\"}],\"outputs\":[]}";
    final SessionDataSource source = new SessionDataSource();
    final AgentEngineRepository repository = new AgentEngineRepository(new JdbcTemplate(source)) {
        @Override public boolean supported() { return supported; }
        @Override public boolean catalogSupported() { return supported; }
        @Override public boolean storedSupported() { return supported; }
        @Override public String list() { return "[" + detail + "]"; }
        @Override public String describe(String name) { return detail; }
        @Override public AgentEngine.Result runStored(AgentEngine.StoredRun input) {
            executions++;
            if (fails) throw new IllegalStateException("private provider detail");
            return new AgentEngine.Result("{}", "{}");
        }
        @Override public AgentEngine.Result run(AgentEngine.Run input) {
            executions++;
            if (fails) throw new IllegalStateException("private provider detail");
            return new AgentEngine.Result("{}", "{}");
        }
    };
    final AgentEngineService service = new AgentEngineService(source, repository);
    AgentEngine.Run input(String schema, boolean confirmed) {
        return new AgentEngine.Run(schema, SPEC, "[{\"name\":\"quantity\",\"value\":2}]", confirmed);
    }
    PoolSession session() {
        var s = new PoolSession(new HikariDataSource(), "TEST", () -> {});
        s.initialize(new DatabaseSession(new DatabaseInfo("APP", "APP", "TEST", "SYNTHETIC"), List.of("APP", "OTHER")));
        return s;
    }
    @Test void rejectsMalformedDuplicateMissingAndOversizedInputs() {
        for (String inputs : List.of("{}", "[]", "null", "[{\"name\":\"quantity\",\"value\":2},{\"name\":\"quantity\",\"value\":3}]"))
            assertThatThrownBy(() -> AgentEngine.validate(new AgentEngine.Run("APP", SPEC, inputs, true))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> AgentEngine.validate(new AgentEngine.Run("APP", " ".repeat(500001), "[]", true))).isInstanceOf(RuntimeException.class);
    }
    @Test void consentOwnerAndCapabilityAreCheckedBeforeExecution() {
        try (var s = session()) {
            assertThatThrownBy(() -> service.run(s, input("APP", false))).isInstanceOf(RuntimeException.class);
            supported = false;
            assertThatThrownBy(() -> service.run(s, input("APP", true))).isInstanceOf(RuntimeException.class);
            supported = true;
            assertThatThrownBy(() -> service.run(s, input("OTHER", true))).isInstanceOf(RuntimeException.class);
            s.metadata().selectSchema("OTHER");
            assertThatThrownBy(() -> service.run(s, input("OTHER", true))).isInstanceOf(RuntimeException.class);
            assertThat(executions).isZero();
        }
    }
    @Test void uncertainExecutionIsNeverRetriedOrReportedAsSuccess() {
        try (var s = session()) {
            fails = true;
            assertThatThrownBy(() -> service.run(s, input("APP", true)))
                    .hasMessageNotContaining("private provider detail");
            assertThat(executions).isEqualTo(1);
        }
    }
    @Test void descriptionSupportsObservedJsonStringsAndNativeArrays() {
        assertThat(AgentEngine.description(detail).path("inputs").isArray()).isTrue();
        assertThat(AgentEngine.description("{\"name\":\"A\",\"inputs\":\"[]\",\"outputs\":\"[]\"}").path("outputs").isArray()).isTrue();
        for (String bad : List.of("[]", "null", "{\"name\":\"A\",\"inputs\":\"broken\",\"outputs\":[]}"))
            assertThatThrownBy(() -> AgentEngine.description(bad)).isInstanceOf(RuntimeException.class);
    }
    AgentEngine.StoredRun stored(String schema, String contract, boolean confirmed) {
        return new AgentEngine.StoredRun(schema, "FLOW_A", "[{\"name\":\"quantity\",\"value\":2}]", contract, confirmed);
    }
    @Test void catalogReadsNeverExecuteAndRespectOwner() {
        try (var s = session()) {
            assertThat(service.list(s, "APP")).hasSize(1);
            assertThat(service.describe(s, "APP", "FLOW_A").path("name").asText()).isEqualTo("FLOW_A");
            s.metadata().selectSchema("OTHER");
            assertThatThrownBy(() -> service.list(s, "OTHER")).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> service.describe(s, "OTHER", "FLOW_A")).isInstanceOf(RuntimeException.class);
            assertThat(executions).isZero();
        }
    }
    @Test void storedExecutionRequiresFreshContractConsentAndOwner() {
        try (var s = session()) {
            assertThatThrownBy(() -> service.runStored(s, stored("APP", detail, false))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> service.runStored(s, stored("APP", "{}", true))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> service.runStored(s, stored("OTHER", detail, true))).isInstanceOf(RuntimeException.class);
            supported = false;
            assertThatThrownBy(() -> service.runStored(s, stored("APP", detail, true))).isInstanceOf(RuntimeException.class);
            assertThat(executions).isZero();
            supported = true;
            service.runStored(s, stored("APP", detail, true));
            assertThat(executions).isEqualTo(1);
            fails = true;
            assertThatThrownBy(() -> service.runStored(s, stored("APP", detail, true))).hasMessageNotContaining("private provider detail");
            assertThat(executions).isEqualTo(2);
        }
    }
}
