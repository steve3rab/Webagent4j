package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;

/**
 * Demonstrates attribute extraction and typed list extraction: reads a link's {@code href}, then
 * every product name on the page as a single, deterministically ordered list.
 */
public final class ExtractAttributeExample {

    private ExtractAttributeExample() {}

    /** Runs against a page containing a link named "Details" and a list of product names. */
    public static void main(String[] args) {
        String url = requireArgument(args, "page URL");
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                var page = browser.open(url)) {
            ExtractionResult<String> detailsLink =
                    page.extract(
                            ExtractionRequest.attribute(
                                    LocatorDefinition.forRole(ElementRole.LINK).named("Details"),
                                    "href"));
            System.out.println("Details link: " + detailsLink.value());

            ExtractionResult<java.util.List<String>> productNames =
                    page.extractList(
                            ExtractionRequest.text(
                                    LocatorDefinition.css("[data-testid='product-name']")));
            System.out.println("Products: " + productNames.value());
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
