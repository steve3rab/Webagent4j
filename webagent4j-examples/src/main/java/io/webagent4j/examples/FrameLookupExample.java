package io.webagent4j.examples;

import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IFrame;
import io.webagent4j.core.WebAgent;

/**
 * Demonstrates the simplest public frame lookup: resolve one {@code <iframe>} by its stable {@code
 * name} attribute, then interact with a target inside it exactly like any page-level {@code find()}
 * chain.
 */
public final class FrameLookupExample {

    private FrameLookupExample() {}

    /** Runs against a page containing an iframe named "checkout" with a "Pay" button inside. */
    public static void main(String[] args) {
        String url = requireArgument(args, "page URL");
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                var page = browser.open(url)) {
            IFrame checkout = page.frame().named("checkout").single();

            ActionResult<Void> result =
                    checkout.action()
                            .click(checkout.find().button().named("Pay").reference())
                            .execute();
            result.throwIfFailed();
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
