package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.db.ProfileAuditProbeSql.Run;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.ProfileHistory.*;
import com.dbcompanion.repository.ProfileAuditProbeRepository;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Fixed, approved ADMIN-only integration verification. No arbitrary SQL/values accepted over HTTP. */
@Service
@Profile("profile-audit-diagnostics")
public class ProfileHistoryVerificationService {
    public static final List<String> VALUES = List.of(
            "검증용 지침 v1: 승인된 정의만 사용하세요.\n날짜는 YYYY-MM-DD로 표시하세요.",
            "검증용 지침 v2: 승인된 정의만 사용하고 근거를 표시하세요.\n날짜는 YYYY-MM-DD로 표시하세요.");
    private final SessionDataSource source;
    private final ProfileAuditProbeRepository probe;
    private final ProfileHistoryService history;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private int attempts;
    public ProfileHistoryVerificationService(SessionDataSource source, ProfileAuditProbeRepository probe,
            ProfileHistoryService history, JsonMapper json) {
        this.source = source; this.probe = probe; this.history = history; this.json = json;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source)); transaction.setTimeout(15);
    }
    public record Report(Run run, String profileId, List<String> steps, List<Entry> entries,
                         boolean changesVerified, boolean offVerified, int snapshotVersions, int requestVersions) {
        public boolean completed() { return changesVerified && offVerified && snapshotVersions == 2 && requestVersions == 2; }
    }
    public static void requireAdmin(String user, String schema) {
        if (!"ADMIN".equals(user) || !"ADMIN".equals(schema))
            throw new IllegalStateException("Log in as ADMIN and select ADMIN schema before verification");
    }
    private <T> T withProbe(PoolSession session, Supplier<T> action) {
        source.bind(session.pool(), "ADMIN");
        try { return transaction.execute(s -> action.get()); } finally { source.clear(); }
    }
    private void identity(PoolSession session, Run run, String id) {
        var rows = withProbe(session, () -> probe.profile(run));
        if (rows.size() != 1 || !id.equals(rows.getFirst().get("PROFILE_ID"))
                || !run.description().equals(rows.getFirst().get("DESCRIPTION")) || !"DISABLED".equals(rows.getFirst().get("STATUS")))
            throw new IllegalStateException("Test profile identity/status changed; no further profile modification allowed");
    }
    public Report run(PoolSession session) {
        synchronized (history) {
        synchronized (session) {
            requireAdmin(session.metadata().info().username(), session.metadata().selectedSchema());
            if (attempts >= 2) throw new IllegalStateException("History verification limit reached (2); inspect evidence before retrying");
            var initial = history.state(session, new Target("ADMIN", null));
            if (!Boolean.FALSE.equals(initial.enabled()) || !initial.canManage()) throw new IllegalStateException("Verification requires an OFF, manageable ADMIN app audit policy");
            var identity = withProbe(session, () -> probe.preflight());
            var run = new Run(UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT));
            if (!withProbe(session, () -> probe.profile(run)).isEmpty()) throw new IllegalStateException("Test profile name collision; nothing changed");
            attempts++;
            var steps = new ArrayList<String>(); List<Entry> rows = List.of();
            String id = null, stage = "ADMIN account audit ON"; boolean manageAttempted = false, changed = false, off = false;
            Target target = new Target("ADMIN", run.profile());
            try {
                manageAttempted = true;
                var enabled = history.toggle(session, new Toggle("ADMIN", true));
                if (!Boolean.TRUE.equals(enabled.enabled()) || !enabled.installed()) throw new IllegalStateException("Audit ON/archive installation not verified");
                steps.add("ADMIN account audit ON / archive installed: verified");
                stage = "create disabled test profile " + run.profile();
                withProbe(session, () -> { probe.execute(ProfileAuditProbeSql.createProfile(run, identity.packageOwner())); return null; });
                var created = withProbe(session, () -> probe.profile(run));
                if (created.size() != 1) throw new IllegalStateException("Created test profile could not be read");
                id = created.getFirst().get("PROFILE_ID"); identity(session, run, id);
                steps.add("ADMIN." + run.profile() + " / ID=" + id + " / DISABLED: verified; retained for UI review");
                stage = "collect initial snapshot"; steps.add(stage + ": " + history.collect(session, target));
                for (int i = 0; i < VALUES.size(); i++) {
                    stage = "write/readback instruction v" + (i + 1); identity(session, run, id);
                    var input = new InstructionAuditProbe.Input("HISTORY_V" + (i + 1), InstructionAuditProbe.Transport.LITERAL, VALUES.get(i));
                    withProbe(session, () -> { probe.instruction(run, identity.packageOwner(), input); return null; });
                    var attrs = withProbe(session, () -> probe.attributes(run));
                    String actual = attrs.stream().filter(r -> "additional_instructions".equals(r.get("ATTRIBUTE_NAME")))
                            .map(r -> r.get("ATTRIBUTE_VALUE")).findFirst().orElse(null);
                    if (!input.value().equals(actual)) throw new IllegalStateException("Actual instruction did not match expected value");
                    steps.add(stage + ": exact match / " + actual);
                    stage = "collect instruction v" + (i + 1); steps.add(stage + ": " + history.collect(session, target));
                }
                changed = true;
                stage = "repeat collection"; steps.add(stage + ": " + history.collect(session, target));
            } catch (RuntimeException ex) { steps.add(error(stage, ex)); }
            finally {
                if (manageAttempted) {
                    try {
                        off = Boolean.FALSE.equals(history.toggle(session, new Toggle("ADMIN", false)).enabled());
                        steps.add("ADMIN account audit OFF: " + off);
                    } catch (RuntimeException ex) { steps.add(error("ADMIN account audit OFF — inspect immediately", ex)); }
                }
                if (id != null) {
                    try {
                        identity(session, run, id);
                        rows = history.history(session, target, "profile", 1).entries();
                        steps.add("Retained test profile and archived rows: " + rows.size());
                    } catch (RuntimeException ex) { steps.add(error("read retained profile/history", ex)); }
                }
            }
            return report(run, id, steps, rows, changed, off);
        }
        }
    }
    public Report refresh(PoolSession session, Report previous) {
        synchronized (history) {
        synchronized (session) {
            requireAdmin(session.metadata().info().username(), session.metadata().selectedSchema());
            if (previous.profileId() == null) throw new IllegalStateException("No verified test profile to collect");
            identity(session, previous.run(), previous.profileId());
            var state = history.state(session, new Target("ADMIN", null));
            if (!Boolean.FALSE.equals(state.enabled())) throw new IllegalStateException("Verify audit OFF before collecting retained test history");
            var steps = new ArrayList<>(previous.steps());
            var target = new Target("ADMIN", previous.run().profile());
            steps.add("Collect retained evidence only: " + history.collect(session, target));
            return report(previous.run(), previous.profileId(), steps, history.history(session, target, "profile", 1).entries(), previous.changesVerified(), true);
        }
        }
    }
    private Report report(Run run, String id, List<String> steps, List<Entry> entries, boolean changed, boolean off) {
        Set<String> snapshots = new HashSet<>(), requests = new HashSet<>();
        for (var entry : entries) {
            var payload = json.readTree(entry.payload());
            var value = entry.kind().equals("SNAPSHOT") ? payload.path("attributes").path("additional_instructions")
                    : entry.kind().equals("REQUEST") && "additional_instructions".equals(payload.path("request").path("attribute").stringValue())
                      ? payload.path("request").path("value") : null;
            if (value != null && value.isString() && VALUES.contains(value.stringValue())) {
                if (entry.kind().equals("SNAPSHOT")) snapshots.add(value.stringValue()); else requests.add(value.stringValue());
            }
        }
        return new Report(run, id, List.copyOf(steps), entries, changed, off, snapshots.size(), requests.size());
    }
    private static String error(String stage, RuntimeException ex) {
        String detail = ex instanceof MetadataEditException edit ? edit.userMessage() : OracleErrorDetails.forDisplay(ex);
        return stage + ": " + (detail == null || detail.isBlank() ? ex.getMessage() : detail);
    }
}
