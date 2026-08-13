package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import org.junit.jupiter.api.Test;

class MissingActionTargetIT {

    @Test
    void returnsTargetNotFoundWithoutBackendInteraction() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/click")) {
            var result =
                    page.action()
                            .click(page.find().button().named("Missing").reference())
                            .execute();
            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_NOT_FOUND);
        }
    }
}
