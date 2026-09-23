package com.dbcompanion.repository;

import com.dbcompanion.model.AgentCatalog.Kind;
import com.dbcompanion.model.AgentCatalog.Scope;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentCatalogQueryTest {
    private AgentViewColumns columns(Kind kind) {
        return new AgentViewColumns(List.of(kind.prefix + "_ID", kind.prefix + "_NAME", "OWNER",
                "DESCRIPTION", "STATUS", "CREATED", "LAST_MODIFIED"));
    }

    @Test void everyKindSupportsWholeOwnSchemaList() {
        for (Kind kind : Kind.values()) {
            var queries = AgentCatalogRepository.itemQueries(new Scope("ADMIN", true), kind, null, columns(kind));
            assertThat(queries).hasSize(1);
            assertThat(queries.getFirst().sql()).contains(" FROM USER_" + kind.view + " WHERE 1=1")
                    .endsWith(" ORDER BY \"" + kind.prefix + "_NAME\"")
                    .doesNotContain(" IN (", "\"OWNER\"", "ADMIN");
            assertThat(queries.getFirst().args()).isEmpty();
        }
    }

    @Test void everyCrossSchemaListBindsOwner() {
        for (Kind kind : Kind.values()) {
            var query = AgentCatalogRepository.itemQueries(new Scope("OTHER_OWNER", false), kind, null, columns(kind)).getFirst();
            assertThat(query.sql()).contains(" FROM DBA_" + kind.view, " AND \"OWNER\" = ?")
                    .doesNotContain("OTHER_OWNER", " IN (");
            assertThat(query.args()).containsExactly("OTHER_OWNER");
        }
    }

    @Test void emptySelectionDoesNotDescribeOrQuery() {
        for (Kind kind : Kind.values()) {
            assertThat(AgentCatalogRepository.itemQueries(new Scope("ADMIN", true), kind, List.of(),
                    new AgentViewColumns(List.of()))).isEmpty();
        }
    }

    @Test void selectedNamesStayBoundAndSchemaScoped() {
        var name = "X' OR 1=1 --";
        var query = AgentCatalogRepository.itemQueries(new Scope("OTHER", false), Kind.TOOL,
                List.of(name, "SECOND"), columns(Kind.TOOL)).getFirst();
        assertThat(query.sql()).contains(" AND \"OWNER\" = ?", " AND \"TOOL_NAME\" IN (?,?)")
                .doesNotContain(name, "SECOND");
        assertThat(query.args()).containsExactly("OTHER", name, "SECOND");
    }

    @Test void selectedNamesRemainBatchedAtFiveHundred() {
        var names = IntStream.range(0, 501).mapToObj(i -> "TASK_" + i).toList();
        var queries = AgentCatalogRepository.itemQueries(new Scope("OTHER", false), Kind.TASK, names, columns(Kind.TASK));
        assertThat(queries).hasSize(2);
        assertThat(queries.getFirst().args()).hasSize(501).startsWith("OTHER", "TASK_0").endsWith("TASK_499");
        assertThat(queries.getLast().args()).containsExactly("OTHER", "TASK_500");
        assertThat(queries.getLast().sql()).contains(" AND \"OWNER\" = ?", " AND \"TASK_NAME\" IN (?)");
    }

    @Test void missingOwnerFailsClosedAndOptionalColumnsRemainNull() {
        var minimal = new AgentViewColumns(List.of("AGENT_TASK_NAME"));
        var query = AgentCatalogRepository.itemQueries(new Scope("ADMIN", true), Kind.TASK, null, minimal).getFirst();
        assertThat(query.sql()).startsWith("SELECT NULL, \"AGENT_TASK_NAME\", NULL, NULL, NULL, NULL FROM USER_AI_AGENT_TASKS");
        assertThatThrownBy(() -> AgentCatalogRepository.itemQueries(new Scope("OTHER", false), Kind.TASK, null, minimal))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OWNER");
    }
}
