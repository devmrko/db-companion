package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.db.ProfileAuditProbeSql.Run;
import com.dbcompanion.repository.ProfileAuditProbeRepository;
import com.dbcompanion.repository.ProfileAuditProbeRepository.Identity;
import java.sql.Connection;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

@Service
@Profile("profile-audit-diagnostics")
public class ProfileAuditProbeService {
    private final SessionDataSource source;
    private final ProfileAuditProbeRepository repository;
    private int attempts;
    public ProfileAuditProbeService(SessionDataSource source, ProfileAuditProbeRepository repository) {
        this.source = source; this.repository = repository;
    }
    public record Snapshot(String step, List<Map<String, String>> attributes) {}
    public record InstructionAttempt(InstructionAuditProbe.Input input, String actual, String error) {
        public boolean applied() { return error == null && input.value().equals(actual); }
        public InstructionAuditProbe.Fingerprint sent() { return InstructionAuditProbe.fingerprint(input.value()); }
        public InstructionAuditProbe.Fingerprint readback() { return InstructionAuditProbe.fingerprint(actual); }
    }
    public record InstructionEvidence(InstructionAttempt attempt, InstructionAuditParser.Parsed parsed,
                                      boolean auditMatches, int auditRows) {
        public InstructionAuditProbe.Fingerprint recovered() { return InstructionAuditProbe.fingerprint(parsed.value()); }
    }
    public record Report(Run run, Identity identity, List<String> steps, List<Snapshot> snapshots,
                         List<Map<String, String>> evidence, List<String> remaining, boolean experimentCompleted,
                         List<InstructionAttempt> instructions) {
        public Report(Run run, Identity identity, List<String> steps, List<Snapshot> snapshots,
                      List<Map<String, String>> evidence, List<String> remaining, boolean experimentCompleted) {
            this(run, identity, steps, snapshots, evidence, remaining, experimentCompleted, List.of());
        }
        public List<InstructionEvidence> instructionEvidence() {
            var parsed = evidence.stream().map(row -> InstructionAuditParser.parse(row.get("SQL_TEXT"), row.get("SQL_BINDS"))).toList();
            return instructions.stream().map(attempt -> {
                var matching = parsed.stream().filter(p -> attempt.input().id().equals(p.caseId()) && run.profile().equals(p.profile())).toList();
                var item = matching.size() == 1 ? matching.getFirst() : new InstructionAuditParser.Parsed(
                        attempt.input().id(), run.profile(), "additional_instructions", null,
                        matching.isEmpty() ? "해당 감사 기록 없음 / SQL 형식 미인식" : "여러 감사 기록: 자동 판정 안 함");
                return new InstructionEvidence(attempt, item, attempt.input().value().equals(item.value()), matching.size());
            }).toList();
        }
    }

    public Identity inspect(PoolSession session) {
        synchronized (session) {
            source.bind(session.pool(), session.metadata().info().username());
            try { return repository.preflight(); } finally { source.clear(); }
        }
    }
    public Report refresh(PoolSession session, Report report) {
        synchronized (session) {
            if (report.identity() == null || !session.metadata().info().username().equals(report.identity().user()))
                throw new IllegalStateException("Diagnostic identity is not available");
            source.bind(session.pool(), session.metadata().info().username());
            try {
                return new Report(report.run(), report.identity(), report.steps(), report.snapshots(),
                        repository.evidence(report.run(), report.identity().user(), report.identity().packageOwner()),
                        report.remaining(), report.experimentCompleted(), report.instructions());
            } finally { source.clear(); }
        }
    }
    /** DDL commits implicitly. Service explicitly owns cleanup, not a fictitious rollback. */
    public synchronized Report run(PoolSession session) {
        return run(session, false);
    }
    public synchronized Report run(PoolSession session, boolean instructionMode) {
        synchronized (session) {
            if (attempts >= 2) throw new IllegalStateException("Diagnostic run limit reached (2); review evidence before retrying");
            attempts++;
            var run = new Run(UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT));
            var steps = new ArrayList<String>(); var remaining = new ArrayList<String>();
            var snapshots = new ArrayList<Snapshot>();
            var instructions = new ArrayList<InstructionAttempt>();
            List<Map<String, String>> evidence = List.of(), policyIdentity = List.of();
            String profileId = null, stage = "get authenticated connection";
            boolean policyAttempted = false, policyCreated = false, profileAttempted = false, completed = false;
            Identity identity = null; Connection connection = null;
            try {
                source.bind(session.pool(), session.metadata().info().username());
                connection = source.getConnection(); source.clear();
                connection.setAutoCommit(true);
                source.bind(new SingleConnectionDataSource(connection, true), null);
                stage = "read-only preflight"; identity = repository.preflight();
                steps.add("Identity: " + identity.user() + " / " + identity.database() + " / " + identity.service()
                        + "; API owner=" + identity.packageOwner());
                if (!repository.policy(run).isEmpty() || !repository.profile(run).isEmpty())
                    throw new IllegalStateException("Diagnostic name collision; nothing overwritten");
                stage = ProfileAuditProbeSql.createPolicy(run, identity.packageOwner(), identity.sessionId(), identity.instanceId());
                policyAttempted = true; repository.execute(stage); policyCreated = true;
                policyIdentity = repository.policy(run);
                if (policyIdentity.size() != 1) throw new IllegalStateException("Unexpected diagnostic policy definition: " + policyIdentity);
                steps.add(stage + ": created");
                stage = ProfileAuditProbeSql.audit(run, identity.user(), true); repository.execute(stage);
                repository.requireEnabled(run, identity.user()); steps.add(stage + ": enabled scope verified");
                repository.clientId(run.client());
                stage = ProfileAuditProbeSql.createProfile(run, identity.packageOwner());
                profileAttempted = true; repository.execute(stage);
                var profile = repository.profile(run);
                if (profile.size() != 1 || !run.description().equals(profile.getFirst().get("DESCRIPTION")))
                    throw new IllegalStateException("Created profile identity did not match");
                profileId = profile.getFirst().get("PROFILE_ID");
                if (!"DISABLED".equals(profile.getFirst().get("STATUS"))) throw new IllegalStateException("Test profile must be DISABLED");
                steps.add(stage + ": disabled profile created, ID=" + profileId);
                if (instructionMode) {
                    snapshots.add(new Snapshot("Initial attributes", repository.attributes(run)));
                    for (var input : InstructionAuditProbe.inputs()) {
                        String failure = null;
                        stage = input.id() + " / " + input.transport() + " / " + InstructionAuditProbe.fingerprint(input.value());
                        try { repository.instruction(run, identity.packageOwner(), input); }
                        catch (RuntimeException ex) { failure = error(stage, ex); steps.add(failure); }
                        var values = repository.attributes(run);
                        var actual = values.stream().filter(r -> "additional_instructions".equalsIgnoreCase(r.get("ATTRIBUTE_NAME")))
                                .map(r -> r.get("ATTRIBUTE_VALUE")).findFirst().orElse(null);
                        var attempt = new InstructionAttempt(input, actual, failure);
                        instructions.add(attempt);
                        steps.add(stage + ": actual=" + attempt.readback() + "; applied=" + attempt.applied());
                    }
                    completed = true;
                } else {
                snapshot(run, "Initial comments=false", false, snapshots);
                stage = ProfileAuditProbeSql.setComments(run, identity.packageOwner(), true); repository.execute(stage);
                snapshot(run, "Literal comments=true", true, snapshots); steps.add(stage + ": readback matched");
                stage = ProfileAuditProbeSql.boundComments(identity.packageOwner()) + " [profile=" + run.profile() + ", value=false]";
                repository.comments(run, identity.packageOwner(), "false");
                snapshot(run, "JDBC bind comments=false", false, snapshots); steps.add(stage + ": readback matched");
                stage = ProfileAuditProbeSql.consecutiveComments(run, identity.packageOwner()); repository.execute(stage);
                snapshot(run, "One block: true then false", false, snapshots); steps.add(stage + ": final false verified");
                stage = ProfileAuditProbeSql.boundComments(identity.packageOwner()) + " [profile=" + run.profile() + ", value=DBC_INVALID_BOOLEAN]";
                boolean rejected = false;
                try { repository.comments(run, identity.packageOwner(), "DBC_INVALID_BOOLEAN"); }
                catch (RuntimeException ex) { rejected = true; steps.add(error("Expected rejection: " + stage, ex)); }
                snapshot(run, "After invalid boolean attempt", false, snapshots);
                if (!rejected) throw new IllegalStateException("Invalid boolean was not rejected; do not infer failure auditing semantics");
                completed = true;
                }
            } catch (Exception ex) { steps.add(error(stage, ex)); }
            finally {
                if (connection != null && identity != null) {
                    if (profileAttempted) {
                        try {
                            var profile = repository.profile(run);
                            if (!profile.isEmpty()) {
                                if (profileId == null || profile.size() != 1 || !profileId.equals(profile.getFirst().get("PROFILE_ID"))
                                        || !run.description().equals(profile.getFirst().get("DESCRIPTION")))
                                    throw new IllegalStateException("Unconfirmed or changed profile identity; not dropped");
                                repository.execute(ProfileAuditProbeSql.dropProfile(run, identity.packageOwner()));
                            }
                            if (!repository.profile(run).isEmpty()) throw new IllegalStateException("Profile still exists after DROP");
                            steps.add("Profile " + identity.user() + "." + run.profile() + ": absent verified");
                        } catch (Exception ex) { steps.add(error("cleanup profile " + run.profile(), ex)); remaining.add("PROFILE " + identity.user() + "." + run.profile()); }
                    }
                    try { evidence = repository.evidence(run, identity.user(), identity.packageOwner()); steps.add("Audit rows read: " + evidence.size()); }
                    catch (Exception ex) { steps.add(error("read run-scoped audit evidence", ex)); }
                    if (policyAttempted) {
                        try {
                            var current = repository.policy(run);
                            if (!current.isEmpty()) {
                                if (!policyCreated || policyIdentity.isEmpty() || !policyIdentity.equals(current))
                                    throw new IllegalStateException("Unconfirmed or changed policy definition; not disabled or dropped");
                                repository.execute(ProfileAuditProbeSql.audit(run, identity.user(), false));
                                if (repository.enabled(run)) throw new IllegalStateException("Policy is still enabled; not dropped");
                                repository.execute(ProfileAuditProbeSql.dropPolicy(run));
                            }
                            if (!repository.policy(run).isEmpty() || repository.enabled(run)) throw new IllegalStateException("Policy still visible after cleanup");
                            steps.add("Audit policy " + run.policy() + ": disabled and absent verified");
                        } catch (Exception ex) { steps.add(error("cleanup policy " + run.policy(), ex)); remaining.add("AUDIT POLICY " + run.policy()); }
                    }
                    try { repository.clientId(identity.previousClientId()); }
                    catch (Exception ex) { steps.add(error("restore CLIENT_IDENTIFIER", ex)); }
                }
                source.clear();
                if (connection != null) {
                    try { session.pool().evictConnection(connection); } catch (Exception ex) { steps.add(error("evict diagnostic connection", ex)); }
                    try { connection.close(); } catch (Exception ex) { steps.add(error("close diagnostic connection", ex)); }
                }
            }
            return new Report(run, identity, List.copyOf(steps), List.copyOf(snapshots), List.copyOf(evidence), List.copyOf(remaining), completed, List.copyOf(instructions));
        }
    }
    private void snapshot(Run run, String step, boolean expected, List<Snapshot> snapshots) {
        var attributes = repository.attributes(run); snapshots.add(new Snapshot(step, attributes));
        var comments = attributes.stream().filter(r -> "comments".equalsIgnoreCase(r.get("ATTRIBUTE_NAME"))).toList();
        if (comments.size() != 1 || !Boolean.toString(expected).equalsIgnoreCase(comments.getFirst().get("ATTRIBUTE_VALUE")))
            throw new IllegalStateException("Comments readback did not match " + expected + ": " + attributes);
    }
    private static String error(String stage, Throwable ex) {
        Throwable cause = ex;
        while (!(cause instanceof java.sql.SQLException) && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof java.sql.SQLException sql)
            return stage + ": Oracle code=" + sql.getErrorCode() + " " + sql.getMessage();
        String oracle = OracleErrorDetails.forDisplay(ex);
        return stage + ": " + (oracle.isEmpty() ? ex.getClass().getSimpleName() + ": " + ex.getMessage() : oracle);
    }
}
