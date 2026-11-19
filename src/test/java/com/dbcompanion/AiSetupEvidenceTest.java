package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.SetupEvidence;
import com.dbcompanion.service.AiExecutionHistoryService;
import com.dbcompanion.service.AiSetupEvidenceService;
import com.dbcompanion.service.ProblemQuestionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiSetupEvidenceTest {
    @Test void deniedSourceDoesNotHideOtherSourceOrBecomeEmpty() {
        var source = new SessionDataSource();
        var conversations = new AiExecutionHistoryService(source, null) {
            @Override public List<SetupEvidence.Item> recentMetadata(PoolSession s, String profile) {
                assertThat(profile).isEqualTo("P");
                throw new IllegalStateException("private error must not reach UI");
            }
        };
        var problems = new ProblemQuestionService(source, null, null) {
            @Override public List<SetupEvidence.Item> recentMetadata(PoolSession s, String profile) {
                return List.of(new SetupEvidence.Item("saved-id", "2026-11-19", "MANUAL_ENTRY"));
            }
        };
        try (var session = new OntologyReadCacheTest().session()) {
            var groups = new AiSetupEvidenceService(conversations, problems).read(session, "P");
            assertThat(groups).extracting(SetupEvidence.Group::state).containsExactly("UNKNOWN", "RECORDED");
            assertThat(groups.get(0).items()).isEmpty();
            assertThat(groups.get(1).items().getFirst().kind()).isEqualTo("MANUAL_ENTRY");
            assertThat(groups.toString()).doesNotContain("private error", "SUCCESS", "CONFIGURED");
        }
    }
    @Test void successfulEmptyReadsAreDifferentFromUnavailable() {
        var source = new SessionDataSource();
        var conversations = new AiExecutionHistoryService(source, null) {
            @Override public List<SetupEvidence.Item> recentMetadata(PoolSession s, String p) { return List.of(); }
        };
        var problems = new ProblemQuestionService(source, null, null) {
            @Override public List<SetupEvidence.Item> recentMetadata(PoolSession s, String p) { return List.of(); }
        };
        try (var session = new OntologyReadCacheTest().session()) {
            assertThat(new AiSetupEvidenceService(conversations, problems).read(session,"P"))
                    .extracting(SetupEvidence.Group::state).containsExactly("EMPTY", "EMPTY");
        }
    }
    @Test void foreignOwnerAndBlankProfileStopBeforeQueries() {
        var service = new AiSetupEvidenceService(null, null);
        try (var session = new OntologyReadCacheTest().session()) {
            assertThatThrownBy(() -> service.read(session," ")).isInstanceOf(IllegalArgumentException.class);
            session.metadata().selectSchema("OTHER");
            assertThatThrownBy(() -> service.read(session,"P")).hasMessageContaining("owner schema");
        }
    }
}
