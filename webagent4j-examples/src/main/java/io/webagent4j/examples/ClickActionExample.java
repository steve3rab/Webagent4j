package io.webagent4j.examples;

import static io.webagent4j.verification.Verifications.textVisible;

import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;

/** Demonstrates a semantic click with a visible-text postcondition. */
public final class ClickActionExample {

    private ClickActionExample() {}

    /** Runs against a page containing an Add to cart button. */
    public static void main(String[] args) {
        String url = requireArgument(args, "page URL");
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                var page = browser.open(url)) {
            ActionResult<Void> result =
                    page.action()
                            .click(page.find().button().named("Add to cart").reference())
                            .expect(textVisible("1 item"))
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
