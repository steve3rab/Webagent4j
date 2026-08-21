package io.webagent4j.browsercrawler.internal;

import io.webagent4j.browser.ConditionTimeoutException;
import io.webagent4j.browser.IPage;
import io.webagent4j.wait.WaitBudget;
import java.time.Duration;

/**
 * Waits for a navigated page's DOM to stop changing, using {@link IPage#waitForCondition(String,
 * Duration)} - a single, backend-natively-bounded call - rather than a Java-side polling loop that
 * repeatedly calls {@link IPage#evaluate(String)}.
 *
 * <p><b>Why not {@code webagent4j-wait}'s {@code WaitEngine}:</b> an earlier version of this class
 * built a {@code WaitPolicy.withStableFor(...)} and drove it through {@code WaitEngine.await(...)},
 * whose probe called {@code page.evaluate(FINGERPRINT_SCRIPT)} once per poll. {@code WaitEngine}
 * only ever checks {@code WaitBudget.expired()} <em>between</em> probe calls - it has no way to
 * interrupt a single probe call that is already in flight. {@code evaluate()} has no timeout of its
 * own, so a poll that happened to land during a client-side navigation transition (a meta-refresh,
 * a JS {@code location.assign}, or a router push mid-flight) could block the underlying Playwright
 * call indefinitely: no exception, no timeout, no way back to Java until - if ever - the driver
 * call itself returned. See {@code BrowserCrawlerRobustnessIT}'s real-Playwright
 * client-side-navigation-during-stability regression test for the reproduction this class resolves.
 *
 * <p><b>The fix:</b> the entire "is the DOM stable" condition - fingerprint computation, change
 * detection, and the {@code stabilityWindow} bookkeeping - is expressed as one JavaScript predicate
 * and handed to the backend's own native timeout-aware polling primitive ({@link
 * IPage#waitForCondition}; the Playwright adapter maps this onto {@code Page.waitForFunction},
 * which polls entirely driver-side and transparently continues polling in a newly-navigated
 * execution context rather than throwing "context destroyed"). There is exactly one call from this
 * class into the backend per stability wait, and that call - not a loop wrapped around it - is what
 * the backend itself bounds to {@code timeout}. If the backend cannot honor this natively-bounded
 * contract, {@link IPage#waitForCondition} fails explicitly ({@link UnsupportedOperationException})
 * rather than silently falling back to an unbounded loop.
 *
 * <p>{@link #awaitStable} deliberately has no return value: {@code IPage#waitForCondition} itself
 * carries none (see its contract - this caller never needed the fingerprint value, only "did it
 * stabilize in time"), so there is nothing left to wrap in a result type. Success is returning
 * normally; a timeout - whether the backend genuinely observed the condition never settle, or the
 * shared budget was already exhausted before this method could even attempt a backend call - is
 * always a {@link ConditionTimeoutException}, letting {@code BrowserCrawler} classify both the same
 * way it classifies {@link io.webagent4j.browser.NavigationTimeoutException} for navigation: by
 * catching one typed exception, never by inspecting {@code WaitBudget} timing or a message.
 *
 * <p>The fingerprint is four parts: {@code document.readyState}; the total element count; the total
 * count of {@code href}-bearing anchors and image-map areas ({@code a[href]}, {@code area[href]});
 * and a bounded, order-stable digest of the first {@value #HREF_DIGEST_LIMIT} such links' {@code
 * href} attributes, {@code JSON.stringify}-encoded (not delimiter-joined) so an href containing the
 * delimiter cannot collide with a genuinely different sequence. {@value #HREF_DIGEST_LIMIT} is a
 * generous, independently-chosen bound, not a guarantee of exact alignment with {@code
 * ObservationOptions.maxElements(2000)}'s retained set: that bound caps the first 2000 *all-kind*
 * semantic elements in document order (headings, buttons, forms, images, and more, not only links),
 * so a link that is, say, the 1800th link on a page but the 2400th semantic element overall would
 * be covered by this digest yet still missing from a truncated observation, and vice versa on a
 * link-dense page. See {@code docs/browser-crawler.md#stability} for the exact, non-overclaiming
 * contract.
 *
 * <p>The "stable since" timestamp inside the fingerprint predicate uses the page's own {@code
 * performance.now()} - monotonic within one document/execution context, immune to system wall-clock
 * changes - never {@code Date.now()}. A client-side full-document navigation replaces the execution
 * context entirely, which resets both {@code performance.now()}'s own origin and the {@code
 * window}-scoped state this predicate tracks its "since" timestamp on; that reset is intentional,
 * not a bug to guard against - the newly-navigated document must establish its own stable interval
 * from scratch, exactly as if it were a fresh page.
 *
 * <p>This still is not a general content-change detector (an anchor's visible text, a non-link
 * element's content, or an href change past the digest boundary is not detected), and it still only
 * ever reflects one observation point, never continued monitoring after stability is accepted - see
 * {@code docs/browser-crawler.md#stability}.
 */
public final class PageStabilityWaiter {

    /** Matches {@code ObservationOptions}'s {@code maxElements} default - see the class Javadoc. */
    private static final int HREF_DIGEST_LIMIT = 2000;

    /**
     * A JavaScript function literal - passed to {@link IPage#waitForCondition} - that computes the
     * DOM fingerprint, tracks how long it has read identically using the page's own monotonic
     * {@code performance.now()} (see the class Javadoc), and returns truthy once it has been stable
     * for {@code stabilityWindowMs}. {@code %d} placeholders are filled in by {@link #awaitStable}
     * - the digest limit and the caller's own {@code stabilityWindow}, never a backend default.
     */
    private static final String STABILITY_CONDITION_SCRIPT_TEMPLATE =
            "() => {"
                    + "  const linkSelector = 'a[href],area[href]';"
                    + "  const links = document.querySelectorAll(linkSelector);"
                    + "  const digest = JSON.stringify(Array.prototype.slice.call(links, 0, %d)"
                    + "    .map(function(e) { return e.getAttribute('href'); }));"
                    + "  const fingerprint = document.readyState + ':' + "
                    + "    document.querySelectorAll('*').length + ':' + links.length + ':' + digest;"
                    + "  const now = performance.now();"
                    + "  const state = window.__wa4jStability;"
                    + "  if (!state || state.value !== fingerprint) {"
                    + "    window.__wa4jStability = { value: fingerprint, since: now };"
                    + "    return false;"
                    + "  }"
                    + "  return (now - state.since) >= %d;"
                    + "}";

    /**
     * Waits for {@code page}'s DOM fingerprint to remain unchanged for {@code stabilityWindow}.
     *
     * @throws ConditionTimeoutException if the page does not stabilize within {@code budget}'s
     *     remaining time - either because {@code budget} was already exhausted (by navigation
     *     itself consuming the whole shared budget) before this method could attempt a backend
     *     call, in which case the exception carries no cause, or because the backend's own bounded
     *     wait genuinely timed out, in which case the original {@link ConditionTimeoutException}
     *     thrown by {@link IPage#waitForCondition} propagates unchanged - this method never catches
     *     it, precisely so {@code BrowserCrawler} can preserve its typed provenance
     */
    public void awaitStable(IPage page, WaitBudget budget, Duration stabilityWindow) {
        Duration timeout = budget.remaining();
        if (timeout.toMillis() < 1) {
            throw new ConditionTimeoutException(
                    "stability wait not attempted: the navigationTimeout budget was already"
                            + " exhausted by navigation itself, leaving no time for the stability"
                            + " wait");
        }
        String script =
                String.format(
                        STABILITY_CONDITION_SCRIPT_TEMPLATE,
                        HREF_DIGEST_LIMIT,
                        stabilityWindow.toMillis());
        page.waitForCondition(script, timeout);
    }
}
