package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.dom.IElement;
import org.junit.jupiter.api.Test;

/**
 * Governed Actions V2 P1 fix: real-browser proof that {@code typeSequentially} must not be
 * classified as idempotent. Reuses the {@code /actions/policy-toctou-typesequence} fixture (a
 * single empty text input with a synchronous, in-page {@code oninput} event counter, the same
 * trustworthy oracle used throughout {@link ActionPolicyTargetIdentityIT}).
 */
class TypeSequentiallyIdempotencyIT {

    @Test
    void repeatingTheSameSequenceAppendsRatherThanReproducingTheFirstResult() throws Exception {
        // The framework itself never replays this side effect automatically - this test performs
        // two independent, deliberate invocations only to demonstrate why TYPE_SEQUENCE must not
        // be labeled idempotent: unlike type/fill's value replacement, replaying the exact same
        // command changes observable page state again instead of reproducing the first result.
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/policy-toctou-typesequence")) {
            IElement target = page.find().textbox().named("Confirm").first();

            page.action().typeSequentially(target, "abc").execute();
            assertThat(page.evaluate("document.getElementById('first').value")).isEqualTo("abc");
            assertThat(intField(page, "firstTypeSeqEvents")).isEqualTo(3);

            page.action().typeSequentially(target, "abc").execute();
            assertThat(page.evaluate("document.getElementById('first').value")).isEqualTo("abcabc");
            assertThat(intField(page, "firstTypeSeqEvents")).isEqualTo(6);
        }
    }

    @Test
    void aPreparedTypeSequentiallyPlanIsClassifiedAsNonIdempotent() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/policy-toctou-typesequence")) {
            IElement target = page.find().textbox().named("Confirm").first();

            var plan = page.action().typeSequentially(target, "abc").plan();

            assertThat(plan.idempotency()).isEqualTo(ActionIdempotency.NON_IDEMPOTENT);
        }
    }

    private static int intField(io.webagent4j.browser.IPage page, String windowPropertyName) {
        Object value = page.evaluate("window." + windowPropertyName);
        return ((Number) value).intValue();
    }
}
