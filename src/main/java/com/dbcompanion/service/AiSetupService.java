package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.AiProfileAttribute;
import com.dbcompanion.model.SemanticContext;
import com.dbcompanion.repository.ProfileEditRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Read-only configuration evidence. Never treats metadata as an execution test. */
@Service
public class AiSetupService {
    public enum State { CONFIGURED, ACTION, UNKNOWN }
    public record Check(String key, State state, String link) {}
    public record Observation(String kind,String id,java.time.Instant at,String state,String code) {}
    public static List<Observation> observations(String owner,String profile,
            List<com.dbcompanion.model.SelectAiTest.Outcome> outcomes,
            com.dbcompanion.model.SelectAiTest.ExecutionResult execution) {
        var result=new ArrayList<Observation>();
        var seen=new java.util.HashSet<String>();
        for(var outcome:outcomes) {
            if(outcome==null||outcome.profile()==null||!owner.equals(outcome.profile().selection().owner())
                    ||!profile.equals(outcome.profile().selection().name())||!seen.add(outcome.id()))continue;
            result.add(new Observation(outcome.action().name(),outcome.id(),outcome.requestedAt(),
                    outcome.error()==null?"RETURNED":"FAILED",outcome.code()));
            if(execution!=null&&outcome.id().equals(execution.resultId()))
                result.add(new Observation("EXECUTION",outcome.id(),null,
                        execution.error()==null?"RETURNED":"FAILED",execution.code()));
        }
        return List.copyOf(result);
    }
    private final DatabaseService database;
    private final SemanticContextService semantic;
    private final SessionDataSource source;
    private final ProfileEditRepository profiles;
    public AiSetupService(DatabaseService database, SemanticContextService semantic,
                          SessionDataSource source, ProfileEditRepository profiles) {
        this.database = database; this.semantic = semantic; this.source = source; this.profiles = profiles;
    }
    public static boolean arrayConfigured(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            var node = JsonMapper.builder().build().readTree(value);
            if (node == null || !node.isArray() || node.isEmpty()) return false;
            for (var item : node) {
                if (!item.isObject() || !item.path("owner").isString() || item.path("owner").asString().isBlank()
                        || !item.path("name").isString() || item.path("name").asString().isBlank()) return false;
            }
            return true;
        } catch (RuntimeException ex) { return false; }
    }
    public static List<Check> configuration(List<AiProfileAttribute> attributes, boolean enabled) {
        Map<String,String> values = attributes.stream().filter(a -> a.value() != null)
                .collect(Collectors.toMap(AiProfileAttribute::name, AiProfileAttribute::value, (a,b) -> b));
        var checks = new ArrayList<Check>();
        checks.add(new Check("profile", enabled ? State.CONFIGURED : State.ACTION, "/ai-profiles"));
        for (String key : List.of("provider", "model"))
            checks.add(new Check(key, values.getOrDefault(key, "").isBlank() ? State.ACTION : State.CONFIGURED, "/ai-profiles"));
        checks.add(new Check("objects", arrayConfigured(values.get("object_list")) ? State.CONFIGURED : State.ACTION, "/ai-profiles"));
        return checks;
    }
    public List<Check> diagnose(PoolSession session, String profile, String purpose) {
        synchronized (session) {
            if (!session.metadata().info().username().equals(session.metadata().selectedSchema()))
                return List.of(new Check("owner", State.ACTION, "/ai-profiles"));
            var page = database.profiles(session, profile);
            var checks = new ArrayList<>(configuration(page.attributes(),
                    page.profiles().stream().anyMatch(p -> p.name().equals(profile) && "ENABLED".equals(p.status()))));
            String credential = value(page.attributes(), "credential_name");
            checks.add(new Check("credential", probe(() -> {
                source.bind(session.pool(), session.metadata().info().username());
                try { return profiles.credentials().contains(credential); } finally { source.clear(); }
            }), "/db/credentials"));
            if (!"nl2sql".equals(purpose)) {
                checks.add(new Check("semantic", probe(() -> {
                    var status = semantic.status(session, session.metadata().selectedSchema());
                    if (!status.supported()) return false;
                    String configured = value(page.attributes(), "semantic_source");
                    if (configured.isBlank()) return false;
                    var names = SemanticContext.sources(configured);
                    if (names.isEmpty()) return false;
                    for (var name : names) {
                        if (status.sources().stream().noneMatch(s -> s.name().equals(name.stringValue())
                                && "ENABLED".equals(s.status()))) return false;
                    }
                    return !"mcp".equals(purpose) || status.sources().stream().anyMatch(s -> "mcp".equals(s.type())
                            && "ENABLED".equals(s.status()) && java.util.stream.StreamSupport.stream(names.spliterator(), false)
                            .anyMatch(n -> n.stringValue().equals(s.name())));
                }), "/ai-profiles"));
                checks.add(new Check(purpose,
                        State.UNKNOWN, "mcp".equals(purpose) ? "/ai-profiles" : "/business-glossary"));
            }
            checks.add(new Check("external", State.UNKNOWN, "/db/admin-help"));
            checks.add(new Check("execution", State.UNKNOWN, "/ai-test"));
            return AiSetupCatalog.configuration(purpose,checks);
        }
    }
    private static String value(List<AiProfileAttribute> attributes, String key) {
        return attributes.stream().filter(a -> a.name().equals(key)).map(a -> a.value() == null ? "" : a.value())
                .findFirst().orElse("");
    }
    private static State probe(Supplier<Boolean> action) {
        try { return action.get() ? State.CONFIGURED : State.ACTION; }
        catch (RuntimeException ex) { return State.UNKNOWN; }
    }
}
