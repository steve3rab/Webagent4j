package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScrollActionIT {

    @Test
    void scrollsToAnOffscreenTargetBeforeClicking() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/scroll")) {
            var button = page.find().button().named("Far action").single();
            page.action().scrollTo(button).execute().throwIfFailed();
            assertThat(button.inViewport()).isTrue();
            page.action().click(button).expect(textVisible("Reached")).execute().throwIfFailed();
        }
    }
}
