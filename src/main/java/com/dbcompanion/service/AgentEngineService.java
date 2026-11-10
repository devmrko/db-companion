package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.AgentEngine;
import com.dbcompanion.repository.AgentEngineRepository;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class AgentEngineService {
    private final SessionDataSource source;
    private final AgentEngineRepository repository;
    public AgentEngineService(SessionDataSource source, AgentEngineRepository repository) {
        this.source = source; this.repository = repository;
    }
    private <T> T bound(PoolSession session, String schema, Supplier<T> work) {
        synchronized (session) {
            if (!session.metadata().selectedSchema().equals(schema)) throw AgentEngine.error(409, "선택 스키마가 변경되었습니다. 화면을 새로 여세요.");
            if (!session.metadata().info().username().equals(schema)) throw AgentEngine.error(403, "실행할 스키마의 소유자로 로그인하세요.");
            source.bind(session.pool(), schema);
            try { return work.get(); } finally { source.clear(); }
        }
    }
    public boolean supported(PoolSession session, String schema) {
        return bound(session, schema, repository::supported);
    }
    public java.util.Map<String, Boolean> capability(PoolSession session, String schema) {
        return bound(session, schema, () -> java.util.Map.of("supported", repository.supported(),
                "catalog", repository.catalogSupported(), "stored", repository.storedSupported()));
    }
    public java.util.List<tools.jackson.databind.JsonNode> list(PoolSession session, String schema) {
        return bound(session, schema, () -> {
            requireCatalog();
            var raw = new tools.jackson.databind.json.JsonMapper().readTree(repository.list());
            if (raw == null || !raw.isArray()) throw AgentEngine.error(502, "DB의 등록 목록 형식을 확인하지 못했습니다.");
            var result = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
            for (var item : raw) result.add(AgentEngine.description(item.toString()));
            return java.util.List.copyOf(result);
        });
    }
    public tools.jackson.databind.JsonNode describe(PoolSession session, String schema, String name) {
        AgentEngine.name(name);
        return bound(session, schema, () -> { requireCatalog(); return AgentEngine.description(repository.describe(name)); });
    }
    private void requireCatalog() {
        if (!repository.catalogSupported()) throw AgentEngine.error(409, "등록 목록·상세 조회 API 지원을 확인하지 못했습니다.");
    }
    public AgentEngine.Result runStored(PoolSession session, AgentEngine.StoredRun input) {
        if (input == null || !input.confirmed()) throw AgentEngine.error(400, "등록된 Agent의 실행 범위를 확인하고 동의하세요.");
        AgentEngine.name(input.name());
        if (input.contract() == null || input.contract().length() > 500_000) throw AgentEngine.error(400, "Agent 상세를 다시 조회하세요.");
        return bound(session, input.schema(), () -> {
            requireCatalog();
            if (!repository.storedSupported()) throw AgentEngine.error(409, "이름으로 실행하는 API를 확인하지 못했습니다.");
            var current = AgentEngine.description(repository.describe(input.name()));
            var json = new tools.jackson.databind.json.JsonMapper();
            if (!current.equals(json.readTree(input.contract()))) throw AgentEngine.error(409, "Agent 정보가 변경되었습니다. 상세를 다시 조회하세요.");
            var contract = json.createObjectNode();
            contract.put("component_type", "Flow"); contract.put("agentspec_version", "stored-contract");
            contract.set("inputs", current.path("inputs"));
            AgentEngine.validate(new AgentEngine.Run(input.schema(), contract.toString(), input.inputs(), true));
            try { return repository.runStored(input); }
            catch (RuntimeException ex) { throw AgentEngine.error(502, "실행 결과를 확인하지 못했습니다. 일부 도구가 실행됐을 수 있으므로 DB 상태를 확인하세요. 자동 재시도하지 않습니다."); }
        });
    }
    public AgentEngine.Result run(PoolSession session, AgentEngine.Run input) {
        AgentEngine.validate(input);
        return bound(session, input.schema(), () -> {
            if (!repository.supported()) throw AgentEngine.error(409, "이 DB에서 trace 포함 실행 API를 확인하지 못했습니다.");
            try { return repository.run(input); }
            catch (RuntimeException ex) {
                throw AgentEngine.error(502, "실행 결과를 확인하지 못했습니다. 일부 도구가 실행됐을 수 있습니다. DB 상태를 확인한 뒤 재실행하세요. 자동 재시도하지 않습니다.");
            }
        });
    }
}
