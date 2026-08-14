package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.SemanticElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Prints the buttons from a bounded immutable semantic observation. */
public final class ObservationExample {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObservationExample.class);

    private ObservationExample() {}

    /** Opens the optional URL argument and prints observed button names. */
    public static void main(String[] args) {
        String url = args.length == 0 ? "https://example.com" : args[0];
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                IPage page = browser.open(url)) {
            Observation observation = page.observe();
            LOGGER.info("Observed '{}' at {}", observation.title(), observation.url());
            observation.buttons().stream()
                    .map(SemanticElement::accessibleName)
                    .forEach(name -> LOGGER.info("Button: {}", name));
        }
    }
}
