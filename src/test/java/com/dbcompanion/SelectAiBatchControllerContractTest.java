package com.dbcompanion;

import com.dbcompanion.controller.SelectAiBatchController;
import com.dbcompanion.controller.SelectAiTestController;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SelectAiBatchControllerContractTest {
    private final JsonMapper json=new JsonMapper();
    @Test void batchDtosRejectClientSuppliedFieldsOutsideTheirExplicitContract(){
        assertThatThrownBy(()->json.readValue("{\"parentIds\":[\"11111111-1111-4111-8111-111111111111\"],\"profile\":\"P\",\"sql\":\"SELECT 1\"}",SelectAiBatchController.Prepare.class)).hasMessageContaining("Unexpected request field");
        assertThatThrownBy(()->json.readValue("{\"generation\":\"g\",\"token\":\"t\",\"consent\":true,\"parentId\":\"client\"}",SelectAiBatchController.Run.class)).hasMessageContaining("Unexpected request field");
        assertThatThrownBy(()->json.readValue("{\"generation\":\"g\",\"token\":\"t\",\"confirmed\":true,\"result\":\"client\"}",SelectAiBatchController.Save.class)).hasMessageContaining("Unexpected request field");
    }
    @Test void selectedResultDtosAcceptOnlyTheirEndpointSpecificPayload(){
        var single=json.readValue("{\"resultId\":\"r\",\"saveToken\":\"t\",\"description\":\"d\",\"expected\":\"e\",\"expectedSql\":\"\",\"status\":\"RECEIVED\",\"includeSnapshots\":true}",SelectAiTestController.SaveProblem.class);
        assertThat(single.resultId()).isEqualTo("r");
        var comparison=json.readValue("{\"generation\":\"g\",\"side\":\"left\",\"resultId\":\"r\",\"saveToken\":\"t\",\"parentId\":\"p\",\"includeSnapshots\":true}",SelectAiTestController.ComparisonSave.class);
        assertThat(comparison.side()).isEqualTo("left");
        assertThatThrownBy(()->json.readValue("{\"resultId\":\"r\",\"saveToken\":\"t\",\"parentId\":\"p\",\"kind\":\"single\",\"includeSnapshots\":true}",SelectAiTestController.SaveProblemAttempt.class)).hasMessageContaining("Unexpected request field");
    }
    @Test void conditionPreviewRequiresStructuredActualValuesAndRejectsExtraFields(){
        var value=json.readValue("{\"action\":\"SQL\",\"question\":\"q\",\"originalQuestion\":\"q\",\"confirmationQuestion\":\"period?\",\"confirmationAnswer\":\"January\",\"conditions\":[{\"topic\":\"기간\",\"value\":\"2026-01\"}],\"useOntology\":false,\"evidenceHash\":\"\"}",SelectAiTestController.ConditionPreview.class);
        assertThat(value.conditions().getFirst().value()).isEqualTo("2026-01");
        assertThatThrownBy(()->json.readValue("{\"action\":\"SQL\",\"question\":\"q\",\"originalQuestion\":\"q\",\"conditions\":[],\"useOntology\":false,\"evidenceHash\":\"\",\"inferredDate\":\"tomorrow\"}",SelectAiTestController.ConditionPreview.class)).hasMessageContaining("Unexpected request field");
    }
}
