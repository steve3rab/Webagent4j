package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.IPage;
import io.webagent4j.browser.InteractionContext;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.IElementReference;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves structured-scope discovery and TOCTOU revalidation never mutate application-owned DOM.
 * Physical identity is tracked purely in-memory in the isolated selector-engine realm, never via a
 * {@code data-webagent4j-scope-id} DOM attribute stamp. TOCTOU protection across a
 * classification-to-use seam (insertion, replacement, reorder, duplicate-ambiguity) is covered
 * separately by {@link ContextScopeIdentityMutationIT}.
 */
class StructuredScopeDomMutationIT {

    @Test
    void resolvingAStructuredScopeLeavesAPreExistingScopeIdAttributeByteForByteUnchanged()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-scope-preexisting-attribute")) {
            ActionResult<Void> result = page.action().click(target(page)).execute();

            assertThat(result.success()).isTrue();
            assertThat(
                            page.evaluate(
                                    "() => document.getElementById('shipping-original')"
                                            + ".getAttribute('data-webagent4j-scope-id')"))
                    .isEqualTo("app-owned-value");
        }
    }

    @Test
    void resolvingAStructuredScopeNeverAddsTheScopeIdAttributeToAContainerThatLacksIt()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-scope-insert-before-use")) {
            ActionResult<Void> result = page.action().click(target(page)).execute();

            assertThat(result.success()).isTrue();
            assertThat(
                            page.evaluate(
                                    "() => document.getElementById('shipping-original')"
                                            + ".hasAttribute('data-webagent4j-scope-id')"))
                    .isEqualTo(false);
        }
    }

    @Test
    void structuredScopeResolutionTriggersZeroObservedAttributeMutations() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-scope-preexisting-attribute")) {
            page.evaluate(
                    """
                    () => {
                      window.__webagent4jObservedMutations = 0;
                      const target = document.getElementById('shipping-original');
                      new MutationObserver(mutations => {
                        window.__webagent4jObservedMutations += mutations.length;
                      }).observe(target, { attributes: true, subtree: true, childList: true });
                    }
                    """);

            ActionResult<Void> result = page.action().click(target(page)).execute();

            assertThat(result.success()).isTrue();
            assertThat(page.evaluate("() => window.__webagent4jObservedMutations")).isEqualTo(0);
        }
    }

    @Test
    void repeatedResolutionDoesNotExpireAnEarlierLiveScopeOrMutateApplicationDom()
            throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/context-scope-preexisting-attribute")) {
            IElement first =
                    page.find(InteractionContext.context().containingText("Shipping"))
                            .button()
                            .named("Continue")
                            .timeout(Duration.ofSeconds(5))
                            .single();

            /*
             * This deliberately exceeds the former 256-token retention policy. The first resolved
             * element must remain usable because later resolutions of the same physical scope no
             * longer own or evict historical leases.
             */
            for (int attempt = 0; attempt < 300; attempt++) {
                page.find(InteractionContext.context().containingText("Shipping"))
                        .button()
                        .named("Continue")
                        .timeout(Duration.ofSeconds(5))
                        .single();
            }

            assertThat(first.state().present()).isTrue();

            assertThat(
                            page.evaluate(
                                    "() => document.getElementById('shipping-original')"
                                            + ".getAttribute('data-webagent4j-scope-id')"))
                    .isEqualTo("app-owned-value");
        }
    }

    private static IElementReference<IElement> target(IPage page) {
        return page.find(InteractionContext.context().containingText("Shipping"))
                .button()
                .named("Continue")
                .timeout(Duration.ofSeconds(5))
                .reference();
    }
}
