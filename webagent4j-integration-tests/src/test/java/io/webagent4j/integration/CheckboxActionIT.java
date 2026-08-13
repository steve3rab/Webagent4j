package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CheckboxActionIT {

    @Test
    void checksIdempotentlyAndUnchecks() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/checkbox")) {
            var checkbox = page.find().checkbox().named("Remember me").single();

            page.action().check(checkbox).execute().throwIfFailed();
            page.action().check(checkbox).execute().throwIfFailed();
            assertThat(checkbox.checked()).isTrue();
            page.action().uncheck(checkbox).execute().throwIfFailed();
            assertThat(checkbox.checked()).isFalse();
        }
    }
}
