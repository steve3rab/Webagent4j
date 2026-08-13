package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;

import org.junit.jupiter.api.Test;

class HoverActionIT {

    @Test
    void revealsHoverOnlySemanticContent() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/hover")) {
            var target = page.find().button().named("Show details").single();
            page.action()
                    .hover(target)
                    .expect(textVisible("Helpful details"))
                    .execute()
                    .throwIfFailed();
        }
    }
}
