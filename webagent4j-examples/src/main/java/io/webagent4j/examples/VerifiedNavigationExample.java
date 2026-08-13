package io.webagent4j.examples;

import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.dom.IElement;
import io.webagent4j.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Executable example covering the first complete semantic browser vertical. */
public final class VerifiedNavigationExample {

    private static final Logger LOGGER = LoggerFactory.getLogger(VerifiedNavigationExample.class);

    private VerifiedNavigationExample() {}

    /** Opens example.com, observes it, follows its semantic link, and verifies navigation. */
    public static void main(String[] args) {
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            IPage page = browser.open("https://example.com");
            Observation observation = page.observe();
            LOGGER.info(
                    "Observed '{}' with {} semantic elements",
                    observation.title(),
                    observation.elements().size());

            IElement link = page.find().link().named("More information...").first();
            ActionResult<Void> result =
                    page.action().click(link).expectUrlContains("iana").execute();
            if (!result.success()) {
                throw new IllegalStateException(
                        "Expected navigation was not verified: "
                                + result.failure().orElseThrow().message());
            }
        }
    }
}
