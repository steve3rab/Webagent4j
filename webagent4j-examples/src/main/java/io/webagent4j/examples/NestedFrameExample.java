package io.webagent4j.examples;

import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IFrame;
import io.webagent4j.core.WebAgent;

/**
 * Demonstrates nested frame traversal: an {@link IFrame} exposes its own {@code frame()} entry
 * point, so a frame declared inside another frame's document is resolved strictly inside that
 * parent's scope - it can never accidentally match a same-named frame belonging to the top-level
 * page or a sibling frame.
 */
public final class NestedFrameExample {

    private NestedFrameExample() {}

    /**
     * Runs against a page containing an iframe named "outer", which itself contains an iframe named
     * "inner" with a "Pay" button inside.
     */
    public static void main(String[] args) {
        String url = requireArgument(args, "page URL");
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                var page = browser.open(url)) {
            IFrame outer = page.frame().named("outer").single();
            IFrame inner = outer.frame().named("inner").single();

            ActionResult<Void> result =
                    inner.action().click(inner.find().button().named("Pay").reference()).execute();
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
