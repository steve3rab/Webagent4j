package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IFrame;
import io.webagent4j.core.WebAgent;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.extraction.api.IValueConverter;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import java.math.BigDecimal;

/**
 * Demonstrates frame-scoped extraction with typed conversion: resolves an iframe named "checkout",
 * reads its total as text, and converts it to a {@link BigDecimal} - reusing the exact same live
 * frame re-resolution {@link IFrame#locate} already has, so a frame replaced mid-wait is still
 * followed correctly.
 */
public final class ExtractFromFrameExample {

    private ExtractFromFrameExample() {}

    /** Runs against a page containing an iframe named "checkout" with a "Total" heading inside. */
    public static void main(String[] args) {
        String url = requireArgument(args, "page URL");
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                var page = browser.open(url)) {
            IFrame checkout = page.frame().named("checkout").single();

            ExtractionResult<BigDecimal> total =
                    checkout.extract(
                            ExtractionRequest.text(
                                            LocatorDefinition.forRole(ElementRole.HEADING)
                                                    .named("Total"))
                                    .convert(IValueConverter.toBigDecimal()));

            System.out.println("Checkout total: " + total.value());
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
