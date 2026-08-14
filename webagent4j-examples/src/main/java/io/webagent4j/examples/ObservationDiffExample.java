package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IPage;
import io.webagent4j.core.WebAgent;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Captures fresh snapshots around a page mutation and computes their semantic diff. */
public final class ObservationDiffExample {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObservationDiffExample.class);

    private ObservationDiffExample() {}

    /** Opens the optional URL argument and demonstrates an in-page semantic change. */
    public static void main(String[] args) {
        String url = args.length == 0 ? "https://example.com" : args[0];
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                IPage page = browser.open(url)) {
            Observation before = page.observe();
            page.evaluate(
                    "document.body.insertAdjacentHTML('beforeend',"
                            + " '<div role=\"status\">Example change</div>')");
            Observation after = page.observe();
            ObservationDiff diff = before.diff(after);
            LOGGER.info(
                    "Semantic change: added={}, removed={}, changed={}",
                    diff.elementsAdded().size(),
                    diff.elementsRemoved().size(),
                    diff.elementsChanged().size());
        }
    }
}
