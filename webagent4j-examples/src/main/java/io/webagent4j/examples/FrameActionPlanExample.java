package io.webagent4j.examples;

import io.webagent4j.action.ActionResult;
import io.webagent4j.action.IActionPlan;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IFrame;
import io.webagent4j.core.WebAgent;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.IElementReference;

/**
 * Demonstrates {@code dryRun()} and {@link IActionPlan} for a frame-scoped action: a dry run
 * resolves the frame and the target and checks preconditions without ever clicking, and a plan
 * built once is only actually executed - re-resolving both the frame boundary and the target fresh
 * - when {@link IActionPlan#execute()} is called.
 */
public final class FrameActionPlanExample {

    private FrameActionPlanExample() {}

    /** Runs against a page containing an iframe named "checkout" with a "Pay" button inside. */
    public static void main(String[] args) {
        String url = requireArgument(args, "page URL");
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                var page = browser.open(url)) {
            IFrame checkout = page.frame().named("checkout").single();
            IElementReference<IElement> payButton =
                    checkout.find().button().named("Pay").reference();

            ActionResult<Void> dryRunResult = checkout.action().click(payButton).dryRun().execute();
            dryRunResult.throwIfFailed();

            IActionPlan<Void> plan = checkout.action().click(payButton).plan();
            ActionResult<Void> result = plan.execute();
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
