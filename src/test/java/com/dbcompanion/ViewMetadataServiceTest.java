package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.MetadataEdit.*;
import com.dbcompanion.model.MetadataHistory;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.*;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.dbcompanion.OntologyCatalogRepositoryTest.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic dictionary and JDBC transaction tests; no external metadata is changed. */
class ViewMetadataServiceTest {
    final SessionDataSource source = new SessionDataSource() {
        @Override public Connection getConnection() { return proxy(Connection.class, (p,m,a) -> switch (m.getName()) {
            case "getAutoCommit" -> true;
            case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
            case "equals" -> p == a[0];
            case "hashCode" -> System.identityHashCode(p);
            default -> empty(m.getReturnType());
        }); }
    };
    final class Dictionary extends JdbcTemplate {
        String objectType = "VIEW", current = "before", applied = "after";
        boolean columnExists = true;
        final List<String> reads = new ArrayList<>(), writes = new ArrayList<>();
        final List<List<Object>> bindings = new ArrayList<>();
        Dictionary() { super(source); }
        @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            reads.add(sql); bindings.add(Arrays.asList(args));
            List<String> result;
            if (sql.contains("ALL_OBJECTS")) result = Set.of("TABLE", "VIEW").contains(objectType) ? List.of(objectType) : List.of();
            else if (sql.contains("ALL_VIEWS")) result = objectType.equals("VIEW") ? List.of("V.X") : List.of();
            else if (sql.contains("ALL_TAB_COLUMNS")) result = columnExists ? List.of("C.D") : List.of();
            else if (sql.contains("ALL_TAB_COMMENTS") || sql.contains("ALL_COL_COMMENTS")) result = Collections.singletonList(current);
            else throw new AssertionError("Unexpected dictionary: " + sql);
            return result.stream().map(type::cast).toList();
        }
        @Override public void execute(String sql) { writes.add(sql); current = applied; }
    }
    final Dictionary jdbc = new Dictionary();
    final MetadataRepository metadata = new MetadataRepository(jdbc);
    final MetadataHistoryRepository history = new MetadataHistoryRepository(jdbc) {
        @Override public void requireHealthyIfTracked(Target target) { }
        @Override public MetadataHistory.Page history(Target target, int page) {
            return new MetadataHistory.Page(List.of(new MetadataHistory.Change("1", "event", "now", "APP", target.schema(), target.table(), "C.D", "COMMENT", null,
                    "{\"exists\":true,\"value\":\"before\"}", "{\"exists\":true,\"value\":\"after\"}")), page, false);
        }
    };
    final HistoryReadinessRepository readiness = new HistoryReadinessRepository(jdbc, history) {
        @Override public Map<String,Object> readiness(Target target) { return Map.of("schema", target.schema(), "table", target.table()); }
    };
    final MetadataService service = new MetadataService(source, metadata, readiness, history);

    @Test void viewAndColumnCommentsEditAndReadBackWithExactQuotedNames() {
        for (String column : Arrays.asList(null, "C.D")) try (var session = new OntologyReadCacheTest().session()) {
            jdbc.current = "before";
            var target = new Target("APP", "V.X", column);
            var form = service.edit(session, target, "comment", null);
            assertThat(form.value()).isEqualTo("before");
            var result = service.save(session, new SaveRequest("APP", "V.X", column, "comment", "edit", null, "after", form.version()));
            assertThat(result.verified()).isTrue();
            assertThat(jdbc.writes.getLast()).isEqualTo(column == null
                    ? "COMMENT ON TABLE \"APP\".\"V.X\" IS 'after'" : "COMMENT ON COLUMN \"APP\".\"V.X\".\"C.D\" IS 'after'");
        }
        assertThat(jdbc.reads).anySatisfy(sql -> assertThat(sql).contains("SUBOBJECT_NAME IS NULL", "OBJECT_TYPE IN ('TABLE', 'VIEW')").doesNotContain("V.X"));
        assertThat(jdbc.bindings).contains(List.of("APP", "V.X"), List.of("APP", "V.X", "C.D"));
    }

    @Test void viewHistoryAndReadinessAreAccessibleAndRestoreStillChecksCurrentVersion() {
        try (var session = new OntologyReadCacheTest().session()) {
            var target = new Target("APP", "V.X", "C.D");
            assertThat(service.historyReadiness(session, target)).containsEntry("table", "V.X");
            var page = new MetadataHistoryService(source, metadata, history, readiness).history(session, target, 1);
            assertThat(page.entries()).hasSize(1);
            assertThat(page.entries().getFirst().beforeJson()).contains("before");
            assertThat(page.entries().getFirst().afterJson()).contains("after");
            var form = service.restoreForm(session, new RestoreRequest("APP", "V.X", "C.D", "comment", null, "historic"));
            assertThat(form.currentValue()).isEqualTo("before");
            assertThat(form.value()).isEqualTo("historic");
            assertThat(jdbc.writes).isEmpty();
            jdbc.current = "concurrent change";
            assertThatThrownBy(() -> service.save(session, new SaveRequest("APP", "V.X", "C.D", "comment", "edit", null, "historic", form.version())))
                    .isInstanceOf(MetadataEditException.class);
            assertThat(jdbc.writes).isEmpty();
        }
    }

    @Test void viewAnnotationsAreRejectedForAddEditRestoreAndSaveBeforeAnyDdl() {
        try (var session = new OntologyReadCacheTest().session()) {
            var target = new Target("APP", "V.X", null);
            for (String name : Arrays.asList(null, "NOTE"))
                assertThatThrownBy(() -> service.edit(session, target, "annotation", name)).isInstanceOf(MetadataEditException.class).hasMessageContaining("unsupported");
            assertThatThrownBy(() -> service.restoreForm(session, new RestoreRequest("APP", "V.X", null, "annotation", "NOTE", "old"))).isInstanceOf(MetadataEditException.class);
            for (String mode : List.of("add", "edit"))
                assertThatThrownBy(() -> service.save(session, new SaveRequest("APP", "V.X", null, "annotation", mode, "NOTE", "value", "version"))).isInstanceOf(MetadataEditException.class);
            assertThat(jdbc.writes).isEmpty();
            assertThat(jdbc.reads).noneMatch(sql -> sql.contains("ALL_ANNOTATIONS_USAGE"));
        }
    }

    @Test void missingColumnsAndUnsupportedObjectsNeverReachMetadataSql() {
        var target = new Target("APP", "V.X", "C.D");
        for (String type : List.of("SYNONYM", "PACKAGE", "SEQUENCE", "MISSING")) {
            jdbc.objectType = type;
            assertThatThrownBy(() -> metadata.requireTarget(target)).isInstanceOf(MetadataEditException.class);
        }
        jdbc.objectType = "VIEW"; jdbc.columnExists = false;
        assertThatThrownBy(() -> metadata.requireTarget(target)).isInstanceOf(MetadataEditException.class).hasMessageContaining("Column");
        jdbc.objectType = "TABLE"; jdbc.columnExists = true;
        metadata.requireTarget(target); metadata.requireAnnotationTarget(target);
        assertThat(jdbc.writes).isEmpty();
    }
}
