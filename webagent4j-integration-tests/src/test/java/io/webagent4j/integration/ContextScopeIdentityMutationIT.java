package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.IPage;
import io.webagent4j.browser.InteractionContext;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.IElementReference;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Proves structured scopes preserve semantic DOM identity across the classification-to-use seam.
 * Each fixture mutates the DOM immediately after classification and exposes independent server-side
 * counters for the intended and dangerous decoy targets.
 */
class ContextScopeIdentityMutationIT {

    @Test
    void insertionBeforeTheResolvedContainerCannotRetargetTheActionByIndex() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-scope-insert-before-use")) {
            ActionResult<Void> result =
                    page.action()
                            .click(
                                    target(
                                            page,
                                            """
                                            () => {
                                              const shipping = document.getElementById('shipping-original');
                                              const decoy = document.createElement('section');
                                              decoy.id = 'billing-inserted';
                                              decoy.setAttribute('aria-label', 'Billing');
                                              decoy.innerHTML = '<button '
                                                  + 'onclick="fetch(\\'/count-click/billing-inserted\\')">'
                                                  + 'Continue</button>';
                                              shipping.before(decoy);
                                            }
                                            """))
                            .execute();

            assertThat(result.success()).isTrue();
            assertThat(support.clickCount("shipping-original")).isEqualTo(1);
            assertThat(support.clickCount("billing-inserted")).isZero();
        }
    }

    @Test
    void aDifferentNodeReplacingTheResolvedContainerAtTheSameIndexIsNeverAccepted()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-scope-replace-before-use")) {
            ActionResult<Void> result =
                    page.action()
                            .click(
                                    target(
                                            page,
                                            """
                                            () => {
                                              const replacement = document.createElement('section');
                                              replacement.id = 'billing-replacement';
                                              replacement.setAttribute('aria-label', 'Billing');
                                              replacement.innerHTML = '<button '
                                                  + 'onclick="fetch(\\'/count-click/billing-replacement\\')">'
                                                  + 'Continue</button>';
                                              document.getElementById('shipping-original').replaceWith(replacement);
                                            }
                                            """))
                            .execute();

            assertThat(support.clickCount("shipping-original")).isZero();
            assertThat(support.clickCount("billing-replacement")).isZero();
            assertThat(
                            page.evaluate(
                                    "() => document.getElementById('billing-replacement') !== null"))
                    .isEqualTo(true);
            assertThat(result.success()).isFalse();
            assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
            assertThat(result.failure().orElseThrow().type())
                    .isIn(ActionFailureType.TARGET_NOT_FOUND, ActionFailureType.BACKEND_FAILURE);
        }
    }

    @Test
    void siblingReorderingPreservesTheOriginallyClassifiedContainerIdentity() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-scope-reorder-before-use")) {
            ActionResult<Void> result =
                    page.action()
                            .click(
                                    target(
                                            page,
                                            """
                                            () => {
                                              const shipping = document.getElementById('shipping-original');
                                              document.getElementById('billing-existing').after(shipping);
                                            }
                                            """))
                            .execute();

            assertThat(result.success()).isTrue();
            assertThat(support.clickCount("shipping-original")).isEqualTo(1);
            assertThat(support.clickCount("billing-existing")).isZero();
        }
    }

    @Test
    void aDuplicateSemanticContainerAppearingAfterClassificationFailsClosed() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-scope-duplicate-before-use")) {
            ActionResult<Void> result =
                    page.action()
                            .click(
                                    target(
                                            page,
                                            """
                                            () => {
                                              const duplicate = document.createElement('section');
                                              duplicate.id = 'shipping-duplicate';
                                              duplicate.setAttribute('aria-label', 'Shipping');
                                              duplicate.innerHTML = '<button '
                                                  + 'onclick="fetch(\\'/count-click/shipping-duplicate\\')">'
                                                  + 'Continue</button>';
                                              document.body.appendChild(duplicate);
                                            }
                                            """))
                            .execute();

            assertThat(support.clickCount("shipping-original")).isZero();
            assertThat(support.clickCount("shipping-duplicate")).isZero();
            assertThat(result.success()).isFalse();
            assertThat(result.executionMode()).isEqualTo(ActionExecutionMode.NOT_EXECUTED);
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
        }
    }

    private static IElementReference<IElement> target(IPage page, String mutation) {
        IElementReference<IElement> delegate =
                page.find(InteractionContext.context().containingText("Shipping"))
                        .button()
                        .named("Continue")
                        .timeout(Duration.ofSeconds(5))
                        .reference();
        AtomicBoolean mutated = new AtomicBoolean();
        return () -> {
            IElement resolved = delegate.resolve();
            if (mutated.compareAndSet(false, true)) {
                page.evaluate(mutation);
            }
            return resolved;
        };
    }
}
