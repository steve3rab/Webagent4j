package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.dom.IElement;
import io.webagent4j.policy.PolicyDecision;
import org.junit.jupiter.api.Test;

/**
 * Real-browser proof that an action policy's {@code ALLOW} for one resolved, concrete target can
 * never be silently transferred to a different element that happens to satisfy the exact same
 * semantic locator by the time the backend side effect actually runs - the governed-execution
 * TOCTOU window between policy authorization and backend invocation. Independent proof comes from
 * the fixture's own server-side click counters for each button, not from the library's own
 * success/failure verdict, and the action-under-test path never bypasses governed execution with a
 * raw Playwright click.
 *
 * <p>Two distinct boundaries are covered: a replacement happening before final exact-target
 * verification even runs (proven above by mutating inside policy evaluation itself, which runs
 * strictly before verification), and - in {@link
 * #verifiedHandleBoundBeforeReplacementNeverActsOnTheReplacementAfterward} - a replacement
 * happening after an exact physical handle has already been verified and bound, proving the backend
 * never falls back to a second, independently re-resolved lookup for the actual native call.
 */
class ActionPolicyTargetIdentityIT {

    @Test
    void allowForATargetThatIsReplacedDuringPolicyEvaluationNeverTransfersToTheReplacement()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/policy-toctou")) {
            IElement target = page.find().button().named("Confirm").first();

            ActionResult<Void> result =
                    page.action()
                            .click(target)
                            .policy(
                                    ctx -> {
                                        // Mutate the live DOM synchronously, inside policy
                                        // evaluation itself: the originally-resolved button
                                        // disappears and a different one - matching the exact
                                        // same semantic locator - takes its place before the
                                        // policy decision is even returned.
                                        page.evaluate("replaceFirstWithReplacementSameLocator()");
                                        return PolicyDecision.allow("test.toctou.allowed");
                                    })
                            .execute();

            assertThat(result.success()).isFalse();
            assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
            assertThat(result.executed()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_CHANGED);
            // Independent proof: neither the original target nor its replacement was ever
            // clicked - authorization for the first was never transferred to the second, even
            // though both satisfy the identical semantic locator.
            assertThat(support.clickCount("first")).isZero();
            assertThat(support.clickCount("replacement")).isZero();
        }
    }

    @Test
    void allowForAnUnchangedTargetStillRunsTheBackendExactlyOnce() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/policy-toctou")) {
            IElement target = page.find().button().named("Confirm").first();

            ActionResult<Void> result =
                    page.action()
                            .click(target)
                            .policy(ctx -> PolicyDecision.allow("test.toctou.unchanged.allowed"))
                            .execute();

            assertThat(result.success()).isTrue();
            assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.REAL);
            // The fixture's onclick handler reports its click via an async fetch() the browser
            // has not necessarily delivered to the server yet at the instant execute() returns -
            // bounded polling on the existing observation timeout, not an immediate read, is what
            // proves the side effect actually landed.
            support.awaitClickCount("first", 1);
            assertThat(support.clickCount("replacement")).isZero();
        }
    }

    @Test
    void allowForAFillTargetThatIsReplacedDuringPolicyEvaluationNeverTransfersToTheReplacement()
            throws Exception {
        // Same TOCTOU boundary as the click case above, proven with a value-changing operation
        // rather than a click: an input field, not a button, receives the same governed-execution
        // exact-target guarantee.
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/policy-toctou-fill")) {
            IElement target = page.find().textbox().named("Confirm").first();

            ActionResult<Void> result =
                    page.action()
                            .type(target, "typed-value")
                            .policy(
                                    ctx -> {
                                        page.evaluate(
                                                "replaceFirstInputWithReplacementSameLocator()");
                                        return PolicyDecision.allow("test.toctou.fill.allowed");
                                    })
                            .execute();

            assertThat(result.success()).isFalse();
            assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
            assertThat(result.executed()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_CHANGED);
            // Independent proof: neither the original input nor its replacement ever received the
            // typed value - authorization for the first was never transferred to the second, even
            // though both satisfy the identical semantic locator.
            assertThat(support.clickCount("first")).isZero();
            assertThat(support.clickCount("replacement")).isZero();
        }
    }

    @Test
    void verifiedHandleBoundBeforeReplacementNeverActsOnTheReplacementAfterward() throws Exception {
        // A second, deeper boundary than the TOCTOU cases above: here the exact physical target
        // is already verified and its handle already bound *before* the DOM is mutated - proving
        // the backend's native call consumes that already-bound handle rather than falling back
        // to a second, independent lookup that could land on the replacement. It is acceptable
        // for the call to fail outright, since the bound handle's own node is now detached; what
        // must never happen is the replacement receiving the click.
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/policy-toctou")) {
            IElement target = page.find().button().named("Confirm").first();
            IElement verifiedTarget = target.verifiedForExecution().orElseThrow();

            page.evaluate("replaceFirstWithReplacementSameLocator()");

            try {
                page.actionBackend().click(verifiedTarget);
            } catch (RuntimeException detachedHandle) {
                // Acceptable: the verified handle's own node is now detached from the DOM.
            }

            assertThat(support.clickCount("first")).isZero();
            assertThat(support.clickCount("replacement")).isZero();
        }
    }
}
