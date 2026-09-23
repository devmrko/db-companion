package com.dbcompanion;

import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.AgentCatalog.Assignment;
import com.dbcompanion.service.AgentRelationships;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentRelationshipsTest {
    @Test void preservesAgentTaskMappingsAndRepeatedAgents() {
        assertThat(AgentRelationships.assignments("""
                [{"name":"worker","task":"first"},{"name":"worker","task":"second"}]
                """)).containsExactly(new Assignment("WORKER", "FIRST"), new Assignment("WORKER", "SECOND"));
    }

    @Test void toolsAreNamesAndDuplicatesAreRemoved() {
        assertThat(AgentRelationships.tools("[\"sql\",\"SQL\",\"websearch\"]")).containsExactly("SQL", "WEBSEARCH");
        assertThat(AgentRelationships.tools(null)).isEmpty();
        assertThat(AgentRelationships.tools("[]")).isEmpty();
    }

    @Test void malformedRelationshipsAreNotReportedAsEmpty() {
        for (String value : new String[]{"not-json", "{}", "[{}]", "[{\"name\":\"worker\"}]", "[null]"}) {
            assertThatThrownBy(() -> AgentRelationships.assignments(value)).isInstanceOf(AppException.class);
        }
        assertThatThrownBy(() -> AgentRelationships.assignments(null)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> AgentRelationships.tools("[123]")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> AgentRelationships.tools("[\"\"]")).isInstanceOf(AppException.class);
    }

    @Test void supervisorSupportsPlainAndJsonStringsAndQuotedToolNames() {
        assertThat(AgentRelationships.optionalName("supervisor")).isEqualTo("SUPERVISOR");
        assertThat(AgentRelationships.optionalName("\"supervisor\"")).isEqualTo("SUPERVISOR");
        assertThat(AgentRelationships.optionalName(null)).isNull();
        assertThat(AgentRelationships.tools("[\"\\\"MixedCase\\\"\"]")).containsExactly("MixedCase");
    }
}
