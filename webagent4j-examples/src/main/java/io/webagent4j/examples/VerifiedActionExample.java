package io.webagent4j.examples;

import static io.webagent4j.verification.Verifications.allOf;
import static io.webagent4j.verification.Verifications.textVisible;
import static io.webagent4j.verification.Verifications.urlContains;

import io.webagent4j.action.ObservationCapturePolicy;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import java.time.Duration;

/** Demonstrates polling multiple postconditions and capturing semantic state. */
public final class VerifiedActionExample {

    private VerifiedActionExample() {}

    /** Runs against a page containing a Continue button. */
    public static void main(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException("Expected the page URL as the first argument");
        }
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var page = browser.open(args[0]);
            var result =
                    page.action()
                            .click(page.find().button().named("Continue").reference())
                            .expect(allOf(urlContains("/complete"), textVisible("Completed")))
                            .timeout(Duration.ofSeconds(5))
                            .captureObservations(ObservationCapturePolicy.ALWAYS)
                            .execute();
            result.throwIfFailed();
            System.out.println(result.diff());
        }
    }
}
