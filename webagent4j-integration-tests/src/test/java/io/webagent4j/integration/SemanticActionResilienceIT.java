package io.webagent4j.integration;

import static io.webagent4j.verification.Verifications.textVisible;

import org.junit.jupiter.api.Test;

class SemanticActionResilienceIT {

    @Test
    void ignoresGeneratedIdsClassesAndNestedMarkup() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/click")) {
            var reference = page.find().button().named("Resilient action").reference();
            page.evaluate("document.querySelector('[id^=generated]').className='changed'");
            page.action()
                    .click(reference)
                    .expect(textVisible("Resilient done"))
                    .execute()
                    .throwIfFailed();
        }
    }
}
