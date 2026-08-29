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
 * TOCTOU window between policy authorization and backend invocation.
 *
 * <p>Independent proof comes from synchronous, in-page event counters (for example {@code
 * window.firstClickEvents}) read back through {@link io.webagent4j.browser.IPage#evaluate(String)}
 * immediately after the action completes, never from the library's own success/failure verdict. A
 * page-local counter is the trustworthy oracle here: the fixture's handlers also call {@code
 * fetch(...)} to a server-side counter as a secondary signal, but that call is asynchronous and can
 * still be in flight - or not yet delivered to the server - at the exact instant a Java-side
 * assertion runs, so a bare {@code fetch()}-only check could observe "zero" even after a side
 * effect the browser has already committed to. The action-under-test path never bypasses governed
 * execution with a raw Playwright click.
 *
 * <p>Three distinct boundaries are covered: a replacement happening before final exact-target
 * verification even runs (proven by mutating inside policy evaluation itself, which runs strictly
 * before verification, across a click, a fill, and a form submit); a replacement happening after an
 * exact physical handle has already been verified and bound, in {@link
 * #verifiedHandleBoundBeforeReplacementNeverActsOnTheReplacementAfterward}, proving the backend
 * never falls back to a second, independently re-resolved lookup for the actual native call; and an
 * unchanged target still running the backend exactly once, proving none of this costs an ordinary
 * governed action anything.
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
            // Independent, synchronous proof: neither the original target nor its replacement
            // ever ran its click handler - authorization for the first was never transferred to
            // the second, even though both satisfy the identical semantic locator.
            assertThat(intField(page, "firstClickEvents")).isZero();
            assertThat(intField(page, "replacementClickEvents")).isZero();
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
            assertThat(intField(page, "firstClickEvents")).isEqualTo(1);
            assertThat(intField(page, "replacementClickEvents")).isZero();
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
            // Independent, synchronous proof: neither the original input nor its replacement ever
            // received the typed value or ran its input handler.
            assertThat(intField(page, "firstInputEvents")).isZero();
            assertThat(intField(page, "replacementInputEvents")).isZero();
            assertThat(page.evaluate("document.getElementById('replacement').value")).isEqualTo("");
        }
    }

    @Test
    void allowForASubmitTargetThatIsReplacedDuringPolicyEvaluationNeverTransfersToTheReplacement()
            throws Exception {
        // A third, stronger side effect than click/fill: a form submit, with real navigation
        // suppressed by the fixture's own preventDefault() so the test page never actually
        // navigates away - the submit event itself is still the real, governed side effect under
        // test.
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/policy-toctou-submit")) {
            IElement target = page.find().button().named("Confirm").first();

            ActionResult<Void> result =
                    page.action()
                            .click(target)
                            .policy(
                                    ctx -> {
                                        page.evaluate(
                                                "replaceFirstFormWithReplacementSameLocator()");
                                        return PolicyDecision.allow("test.toctou.submit.allowed");
                                    })
                            .execute();

            assertThat(result.success()).isFalse();
            assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
            assertThat(result.executed()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_CHANGED);
            assertThat(intField(page, "firstSubmitEvents")).isZero();
            assertThat(intField(page, "replacementSubmitEvents")).isZero();
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

            assertThat(intField(page, "firstClickEvents")).isZero();
            assertThat(intField(page, "replacementClickEvents")).isZero();
        }
    }

    /**
     * Reads back a {@code window}-scoped numeric counter, synchronously, as a plain {@code int}.
     */
    private static int intField(io.webagent4j.browser.IPage page, String windowPropertyName) {
        Object value = page.evaluate("window." + windowPropertyName);
        return ((Number) value).intValue();
    }
}
