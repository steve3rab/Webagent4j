package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionStatus;
import org.junit.jupiter.api.Test;

class ActionPreconditionFailureIT {

    @Test
    void rejectsDisabledTargetBeforeBackendClick() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/failure")) {
            var disabled = page.find().button().named("Disabled action").single();
            var result = page.action().click(disabled).execute();
            assertThat(result.status()).isEqualTo(ActionStatus.PRECONDITION_FAILED);
            assertThat(result.failure()).isPresent();
        }
    }
}
