package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Renders an observation as compact deterministic semantic text. */
public final class CompactObservationExample {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompactObservationExample.class);

    private CompactObservationExample() {}

    /** Opens the optional URL argument and logs the compact semantic representation. */
    public static void main(String[] args) {
        String url = args.length == 0 ? "https://example.com" : args[0];
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open(url);
            Observation observation = page.observe();
            LOGGER.info(
                    "Compact observation:{}{}",
                    System.lineSeparator(),
                    observation.toCompactText());
        }
    }
}
