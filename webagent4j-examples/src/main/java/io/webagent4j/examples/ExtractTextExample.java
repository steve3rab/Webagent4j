package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;

/**
 * Demonstrates the simplest extraction: resolve one element by its semantic role and accessible
 * name, and read its normalized visible text - reusing the exact same locator resolution {@code
 * find()}/{@code locate()} already use, not a second DOM search.
 */
public final class ExtractTextExample {

    private ExtractTextExample() {}

    /** Runs against a page containing a heading named "Total". */
    public static void main(String[] args) {
        String url = requireArgument(args, "page URL");
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                var page = browser.open(url)) {
            ExtractionResult<String> total =
                    page.extract(
                            ExtractionRequest.text(
                                    LocatorDefinition.forRole(ElementRole.HEADING).named("Total")));

            System.out.println("Total: " + total.value());
        }
    }

    private static String requireArgument(String[] args, String description) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected " + description + " as the first argument");
        }
        return args[0];
    }
}
