package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import org.junit.jupiter.api.Test;

class AmbiguousActionTargetIT {

    @Test
    void preservesSemanticAmbiguityAsAStructuredFailure() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/ambiguous")) {
            var result =
                    page.action()
                            .click(page.find().button().named("Duplicate").reference())
                            .execute();
            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
        }
    }
}
