package com.dbcompanion;

import com.dbcompanion.repository.AgentViewColumns;
import com.dbcompanion.model.AgentCatalog.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentViewColumnsTest {
    @Test void dictionaryNamesUsePluralObjectViewsAndSingularAttributeViews() {
        assertThat(Kind.TEAM.view).isEqualTo("AI_AGENT_TEAMS");
        assertThat(Kind.AGENT.view).isEqualTo("AI_AGENTS");
        assertThat(Kind.TASK.view).isEqualTo("AI_AGENT_TASKS");
        assertThat(Kind.TOOL.view).isEqualTo("AI_AGENT_TOOLS");
        assertThat(Kind.TASK.attributesView).isEqualTo("AI_AGENT_TASK_ATTRIBUTES");
        assertThat(Kind.TOOL.attributesView).isEqualTo("AI_AGENT_TOOL_ATTRIBUTES");
    }

    @Test void resolvesActualObservedAutonomousTeamColumns() {
        var columns = new AgentViewColumns(List.of("AGENT_TEAM_ID", "AGENT_TEAM_NAME", "STATUS", "DESCRIPTION",
                "CREATED", "LAST_MODIFIED", "ORACLE_MAINTAINED"));
        assertThat(columns.required(Kind.TEAM.nameColumns())).isEqualTo("\"AGENT_TEAM_NAME\"");
        assertThat(columns.projection(Kind.TEAM.idColumns())).isEqualTo("\"AGENT_TEAM_ID\"");
    }

    @Test void prefixedAndDocumentedColumnsAreResolvedOnlyWhenPresent() {
        for (Kind kind : Kind.values()) {
            var documented = new AgentViewColumns(List.of(kind.prefix + "_NAME", kind.prefix + "_ID"));
            assertThat(documented.required(kind.nameColumns())).isEqualTo("\"" + kind.prefix + "_NAME\"");
            if (kind != Kind.AGENT) {
                var prefixed = new AgentViewColumns(List.of("AGENT_" + kind.prefix + "_NAME", "AGENT_" + kind.prefix + "_ID"));
                assertThat(prefixed.required(kind.nameColumns())).isEqualTo("\"AGENT_" + kind.prefix + "_NAME\"");
                assertThat(prefixed.required(kind.idColumns())).isEqualTo("\"AGENT_" + kind.prefix + "_ID\"");
            }
        }
    }

    @Test void absentOptionalColumnsAreNeverReferenced() {
        var columns = new AgentViewColumns(List.of("TEAM_NAME", "DESCRIPTION"));
        assertThat(columns.required("TEAM_NAME")).isEqualTo("\"TEAM_NAME\"");
        assertThat(columns.projection("TEAM_ID", "ID")).isEqualTo("NULL");
        assertThat(columns.projection("STATUS")).isEqualTo("NULL");
        assertThat(columns.projection("LAST_MODIFIED")).isEqualTo("NULL");
        assertThatThrownBy(() -> columns.required("OWNER")).isInstanceOf(IllegalStateException.class);
    }

    @Test void onlyObservedIdentifiersAreResolvedAndQuoted() {
        var columns = new AgentViewColumns(List.of("tool_name", "ATTRIBUTE_NAME", "ATTRIBUTE_VALUE", "LAST_MODIFIED"));
        assertThat(columns.optional("TOOL_NAME", "AGENT_NAME")).isEqualTo("\"tool_name\"");
        assertThat(columns.optional("TOOL_ID")).isNull();
        assertThat(columns.projection("LAST_MODIFIED")).isEqualTo("\"LAST_MODIFIED\"");
    }
}
