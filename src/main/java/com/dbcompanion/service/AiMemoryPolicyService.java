package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.AiMemoryPolicy;
import com.dbcompanion.model.AiMemoryPolicy.*;
import com.dbcompanion.repository.AiMemoryPolicyRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class AiMemoryPolicyService {
    private final SessionDataSource source;
    private final AiMemoryPolicyRepository repository;
    private com.dbcompanion.repository.MemoryBrowseRepository browser;
    @org.springframework.beans.factory.annotation.Autowired
    public void setBrowser(com.dbcompanion.repository.MemoryBrowseRepository browser) { this.browser = browser; }
    public AiMemoryPolicyService(SessionDataSource source, AiMemoryPolicyRepository repository) {
        this.source = source; this.repository = repository;
    }
    public record Status(Capability capability, List<Resource> stores, List<Resource> guardrails,
                         List<String> profiles, Map<String,String> settings) {}
    private <T> T bound(PoolSession session, String schema, Supplier<T> work) {
        synchronized (session) {
            if (!session.metadata().selectedSchema().equals(schema))
                throw AiMemoryPolicy.error(409, "선택한 스키마가 변경되었습니다. 화면을 다시 열어 주세요.");
            if (!session.metadata().info().username().equals(schema))
                throw AiMemoryPolicy.error(403, "프로필 소유자로 로그인해야 합니다.");
            source.bind(session.pool(), schema);
            try { return work.get(); } finally { source.clear(); }
        }
    }
    public Status status(PoolSession session, String schema, String profile) {
        return bound(session, schema, () -> {
            var c = repository.capability();
            if (!c.memory() && !c.guardrail()) return new Status(c, List.of(), List.of(), List.of(), Map.of());
            return new Status(c, repository.stores(c), repository.guardrails(c), repository.profiles(), repository.settings(profile));
        });
    }
    public List<Observation> observations(PoolSession session, String schema, String profile) {
        return bound(session, schema, () -> repository.observations(profile));
    }
    public List<Conversation> conversations(PoolSession session, String schema, String profile) {
        return bound(session, schema, () -> repository.conversations(profile));
    }
    public StoreDetails store(PoolSession session, String schema, String name) {
        return bound(session, schema, () -> repository.storeDetails(name));
    }
    public Object browse(PoolSession session, String schema, String name, String kind, String query, int page) {
        return bound(session, schema, () -> browser.browse(name, kind, query, page));
    }
    public Object record(PoolSession session, String schema, String name, String kind, String id) {
        return bound(session, schema, () -> browser.detail(name, kind, id));
    }
    public Result extract(PoolSession session, Extract input) {
        AiMemoryPolicy.validateExtraction(input);
        return bound(session, input.schema(), () -> {
            repository.validateExtraction(input);
            try {
                repository.extract(input);
                return new Result(true, "DB 추출 호출이 완료되었습니다. 새로운 메모리 생성이나 다음 대화에서의 회상을 보장하지는 않습니다.");
            } catch (RuntimeException ex) {
                return new Result(false, "추출 완료 여부를 확정하지 못했습니다. 비용이 발생했을 수 있으므로 자동 재시도하지 마세요.");
            }
        });
    }
    public synchronized Result create(PoolSession session, Create input) {
        AiMemoryPolicy.validate(input);
        return bound(session, input.schema(), () -> {
            var c = repository.require(input.kind());
            var resources = input.kind().equals("memory") ? repository.stores(c) : repository.guardrails(c);
            if (resources.stream().anyMatch(r -> r.name().equals(input.name())))
                throw AiMemoryPolicy.error(409, "동일한 이름이 이미 있습니다. 기존 구성을 확인하세요.");
            String attributes = repository.attributes(input);
            try {
                repository.create(c, input, attributes);
                if (repository.matches(c, input, attributes))
                    return new Result(true, "생성과 저장 설정을 확인했습니다. 프로필 연결은 별도로 저장하세요.");
                return new Result(false, "생성 호출이 완료되었습니다. 목록을 새로고침하고 DB 설정을 확인하세요. 프로필 연결은 별도로 저장해야 합니다.");
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Memory/guardrail creation outcome requires readback");
                return new Result(false, "생성 결과를 확정하지 못했습니다. 자동 재시도하지 말고 DB의 현재 저장소·정책을 먼저 확인하세요.");
            }
        });
    }
}
