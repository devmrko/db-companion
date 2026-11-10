package com.dbcompanion;

import com.dbcompanion.service.SelectAiReadSql;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SelectAiReadSqlAliasTest {
    @Test void qualifiedAiAliasIsAnOrdinaryQuery() {
        for (String sql : new String[] {
            "SELECT ai.\"LOCATION_ID\" FROM inventory ai",
            "SELECT AI . quantity FROM inventory AI",
            "SELECT ai /* qualifier */ .quantity FROM inventory ai",
            "SELECT ai -- qualifier\n .quantity FROM inventory ai",
            "```sql\nSELECT ai.* FROM inventory ai;\n```"
        }) {
            assertThat(SelectAiReadSql.isQueryResponse(sql)).as(sql).isTrue();
            assertThatCode(() -> SelectAiReadSql.check(sql)).doesNotThrowAnyException();
        }
    }

    @Test void selectAiCommandsRemainBlocked() {
        for (String sql : new String[] {
            "SELECT AI showsql inventory",
            "SELECT /* separator */ AI runsql inventory",
            "SELECT AI -- separator\n narrate inventory",
            "SELECT AI /* a dot inside a comment . */ runsql inventory",
            "SELECT AI",
            "```sql\nSELECT AI chat inventory\n```"
        }) {
            assertThat(SelectAiReadSql.isQueryResponse(sql)).as(sql).isFalse();
            assertThatThrownBy(() -> SelectAiReadSql.check(sql))
                .isInstanceOf(com.dbcompanion.model.AiAssistant.Failure.class);
        }
    }
}
