package com.dbcompanion;

import com.dbcompanion.model.DemoDataPrompt;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DemoDataPromptTest {
    private DemoDataPrompt.Input input() {
        return new DemoDataPrompt.Input("Synthetic game activity", "Business AU by region?", "Exclude test users", "2026-11-24", 12);
    }
    @Test void preventsAdminAndCrossSchemaHandoffs() {
        for (var owner : List.of("ADMIN", "SYS", "SYSTEM", "PUBLIC", "BAD;DROP", "A.B"))
            assertThatThrownBy(() -> DemoDataPrompt.build(owner, owner, input())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoDataPrompt.build("APP_TEST", "OTHER_OWNER", input())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void preservesBusinessInputAndRequiresVerificationBeforeConfiguration() {
        String prompt = DemoDataPrompt.build("APP_TEST", "APP_TEST", input());
        assertThat(prompt).contains("APP_TEST", "Synthetic game activity", "Business AU by region?", "Exclude test users", "2026-11-24", "12", "01-schema.sql", "02-data.sql", "03-verify.sql", "04-business-rules.md", "SESSION_USER", "CURRENT_SCHEMA", "SQLcl", "CUSTOM");
        assertThat(prompt).doesNotContain("{0}", "{1}", "{2}", "{3}", "{4}", "{5}");
    }
    @Test void rejectsUnboundedOrInvalidInputs() {
        for (var value : List.of(new DemoDataPrompt.Input("", "q", "r", "2026-11-24", 12),
                new DemoDataPrompt.Input("s", "q", "r", "not-a-date", 12),
                new DemoDataPrompt.Input("s", "q", "r", "2026-11-24", 10001),
                new DemoDataPrompt.Input("s".repeat(2001), "q", "r", "2026-11-24", 12)))
            assertThatThrownBy(() -> DemoDataPrompt.build("APP_TEST", "APP_TEST", value)).isInstanceOf(IllegalArgumentException.class);
    }
}
