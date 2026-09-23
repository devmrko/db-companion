package com.dbcompanion;

import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.DatabaseInfo;
import com.dbcompanion.model.DatabaseSession;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DatabaseSessionTest {
    private final DatabaseInfo info = new DatabaseInfo("APP", "APP", "service", "database");

    @Test void copiesSchemaListAndRetainsLoginInfo() {
        var schemas = new ArrayList<>(List.of("APP", "REPORT"));
        var session = new DatabaseSession(info, schemas);
        schemas.clear();
        assertThat(session.info()).isSameAs(info);
        assertThat(session.schemas()).containsExactly("APP", "REPORT");
        assertThat(session.selectedSchema()).isEqualTo("APP");
        assertThatThrownBy(() -> session.schemas().add("OTHER")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void refreshPreservesSelectionOrFallsBackToLoginSchema() {
        var session = new DatabaseSession(info, List.of("APP", "REPORT"));
        session.selectSchema("REPORT");
        session.refreshSchemas(List.of("APP", "REPORT", "NEW"));
        assertThat(session.selectedSchema()).isEqualTo("REPORT");
        session.refreshSchemas(List.of("APP", "NEW"));
        assertThat(session.selectedSchema()).isEqualTo("APP");
        assertThat(session.schemas()).containsExactly("APP", "NEW");
        assertThatThrownBy(() -> session.refreshSchemas(List.of("OTHER"))).isInstanceOf(AppException.class);
        assertThat(session.schemas()).containsExactly("APP", "NEW");
        assertThat(session.selectedSchema()).isEqualTo("APP");
    }

    @Test void selectionIsIsolatedAndRejectsUnavailableSchema() {
        var first = new DatabaseSession(info, List.of("APP", "REPORT"));
        var second = new DatabaseSession(info, List.of("APP", "REPORT"));
        first.selectSchema("REPORT");
        assertThat(first.selectedSchema()).isEqualTo("REPORT");
        assertThat(second.selectedSchema()).isEqualTo("APP");
        assertThatThrownBy(() -> first.selectSchema("OTHER")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> first.selectSchema(null)).isInstanceOf(AppException.class);
        assertThat(first.selectedSchema()).isEqualTo("REPORT");
    }
}
