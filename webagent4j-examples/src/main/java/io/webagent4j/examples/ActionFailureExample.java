package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import java.time.Duration;

/** Demonstrates inspecting a normal structured action failure. */
public final class ActionFailureExample {

    private ActionFailureExample() {}

    /** Attempts a missing semantic target and prints safe diagnostics. */
    public static void main(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException("Expected the page URL as the first argument");
        }
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                var page = browser.open(args[0])) {
            var result =
                    page.action()
                            .click(page.find().button().named("Missing action").reference())
                            .timeout(Duration.ofSeconds(1))
                            .execute();
            result.failure()
                    .ifPresent(
                            failure ->
                                    System.out.println(failure.type() + ": " + failure.message()));
        }
    }
}
