package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.ColumnInfo;
import com.dbcompanion.model.Ontology;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.repository.DatabaseRepository;
import com.dbcompanion.repository.OntologyRepository;
import com.dbcompanion.repository.TableStructureRepository;
import com.dbcompanion.service.OntologyGovernanceService;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

/** Uses only synthetic metadata and proxy connections; never contacts Oracle. */
class OntologyGovernanceServiceTest {
    int appends;
    boolean failRead;
    Snapshot current = snapshot("stable", "old", "SUCCESS", "N", List.of());
    Entry baseline = entry(current, 1);
    final SessionDataSource source = new SessionDataSource() {
        @Override public Connection getConnection() {
            return proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
                case "getAutoCommit" -> true;
                case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                case "equals" -> p == a[0];
                case "hashCode" -> System.identityHashCode(p);
                default -> empty(m.getReturnType());
            });
        }
    };
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    final OntologyRepository repository = new OntologyRepository(jdbc, new JsonMapper(),
            new DatabaseRepository(jdbc), new TableStructureRepository(jdbc)) {
        @Override public void require(String schema, String login) { }
        @Override public Entry entry(String schema, String table, int revision) { return baseline; }
        @Override public Snapshot snapshot(String database, String schema, String table) {
            if (failRead) throw new DataAccessResourceFailureException("synthetic read failure");
            return current;
        }
        @Override public Entry append(String schema, String table, int expected, String state, Document document) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(expected).isEqualTo(baseline.revision());
            appends++;
            return new Entry("2", expected + 1, "new-document", state, "APP", "later", document);
        }
    };
    final OntologyGovernanceService service = new OntologyGovernanceService(source, repository);
    private PoolSession session() { return new OntologyReadCacheTest().session(); }
    private static Snapshot snapshot(String comment, String annotation, String status, String nullable, List<Key> keys) {
        return new Snapshot("DB", "APP", "ORDERS", comment,
                List.of(new ColumnInfo(1, "ID", "NUMBER", nullable, "identifier")), keys, "captured",
                List.of(new Ontology.Annotation(null, "MixedTag", annotation, null, null)), status);
    }
    private static Entry entry(Snapshot source, int revision) {
        return new Entry("1", revision, "old-document", "APPROVED", "APP", "earlier",
                new Document(1, source, Ontology.initial(source), "USER", null));
    }
    private void refusesChangedSnapshot(Snapshot replacement) {
        try (var s = session()) {
            var preview = service.drift(s, "APP", "ORDERS");
            current = replacement;
            assertThatThrownBy(() -> service.accept(s, "APP", "ORDERS", 1, preview.checkedAt(), true))
                    .isInstanceOf(Ontology.Failure.class);
            assertThat(appends).isZero();
        }
    }
    @Test void changedCommentAfterPreviewCannotBeAccepted() {
        refusesChangedSnapshot(snapshot("changed", "old", "SUCCESS", "N", List.of()));
    }
    @Test void changedAnnotationAfterPreviewCannotBeAccepted() {
        refusesChangedSnapshot(snapshot("stable", "new", "SUCCESS", "N", List.of()));
    }
    @Test void unconfirmedAnnotationReadAfterPreviewCannotBeAccepted() {
        refusesChangedSnapshot(snapshot("stable", "old", "UNCONFIRMED", "N", List.of()));
    }
    @Test void changedNullableAfterPreviewCannotBeAccepted() {
        refusesChangedSnapshot(snapshot("stable", "old", "SUCCESS", "Y", List.of()));
    }
    @Test void changedKeyAfterPreviewCannotBeAccepted() {
        var key = new Key("PK_ORDERS", "P", List.of("ID"), null, null, List.of(), "ENABLED", "VALIDATED");
        refusesChangedSnapshot(snapshot("stable", "old", "SUCCESS", "N", List.of(key)));
    }
    @Test void newerOntologyRevisionCannotBeOverwritten() {
        try (var s = session()) {
            var preview = service.drift(s, "APP", "ORDERS");
            baseline = entry(current, 2);
            assertThatThrownBy(() -> service.accept(s, "APP", "ORDERS", 1, preview.checkedAt(), true))
                    .isInstanceOf(Ontology.Failure.class);
            assertThat(appends).isZero();
        }
    }
    @Test void failedCurrentReadCannotWriteOrTreatTheTableAsDeleted() {
        try (var s = session()) {
            var preview = service.drift(s, "APP", "ORDERS");
            failRead = true;
            assertThatThrownBy(() -> service.accept(s, "APP", "ORDERS", 1, preview.checkedAt(), true))
                    .isInstanceOf(Ontology.Failure.class);
            assertThat(service.drift(s, "APP", "ORDERS").status()).isEqualTo("READ_UNCONFIRMED");
            assertThat(appends).isZero();
        }
    }
    @Test void confirmationIsRequiredAndConsumedPreviewCannotBeReplayed() {
        try (var s = session()) {
            var preview = service.drift(s, "APP", "ORDERS");
            assertThatThrownBy(() -> service.accept(s, "APP", "ORDERS", 1, preview.checkedAt(), false))
                    .isInstanceOf(Ontology.Failure.class);
            assertThatThrownBy(() -> service.accept(s, "APP", "ORDERS", 1, preview.checkedAt(), true))
                    .isInstanceOf(Ontology.Failure.class);
            assertThat(appends).isZero();
        }
    }
    @Test void reviewedSnapshotCreatesOneNewVersionAndPreservesPreviousVersion() {
        var previous = baseline;
        current = snapshot("reviewed change", "new", "SUCCESS", "N", List.of());
        try (var s = session()) {
            var preview = service.drift(s, "APP", "ORDERS");
            assertThat(preview.status()).isEqualTo("REVIEW_REQUIRED");
            var result = service.accept(s, "APP", "ORDERS", 1, preview.checkedAt(), true);
            assertThat(result.revision()).isEqualTo(2);
            assertThat(result.document().source()).isEqualTo(current);
            assertThat(baseline).isSameAs(previous);
            assertThat(previous.document().source().comment()).isEqualTo("stable");
            assertThatThrownBy(() -> service.accept(s, "APP", "ORDERS", 1, preview.checkedAt(), true))
                    .isInstanceOf(Ontology.Failure.class);
            assertThat(appends).isEqualTo(1);
        }
    }
    @Test void historicalAnnotationBaselineCanBeInitializedOnlyFromConfirmedRead() {
        baseline = entry(snapshot("stable", "old", "UNCONFIRMED", "N", List.of()), 1);
        try (var s = session()) {
            var preview = service.drift(s, "APP", "ORDERS");
            assertThat(preview.status()).isEqualTo("ANNOTATION_BASELINE_REQUIRED");
            assertThat(service.accept(s, "APP", "ORDERS", 1, preview.checkedAt(), true).revision()).isEqualTo(2);
            assertThat(appends).isEqualTo(1);
        }
    }
    @Test void currentAnnotationFailureTakesPriorityOverMissingHistoricalBaseline() {
        current = snapshot("stable", "old", "UNCONFIRMED", "N", List.of());
        baseline = entry(current, 1);
        try (var s = session()) {
            var preview = service.drift(s, "APP", "ORDERS");
            assertThat(preview.status()).isEqualTo("ANNOTATIONS_UNCONFIRMED");
            assertThatThrownBy(() -> service.accept(s, "APP", "ORDERS", 1, preview.checkedAt(), true))
                    .isInstanceOf(Ontology.Failure.class);
            assertThat(appends).isZero();
        }
    }
}
