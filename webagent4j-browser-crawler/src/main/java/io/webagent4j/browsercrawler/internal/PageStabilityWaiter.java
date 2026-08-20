package io.webagent4j.browsercrawler.internal;

import io.webagent4j.browser.IPage;
import io.webagent4j.wait.IWaitProbe;
import io.webagent4j.wait.WaitBudget;
import io.webagent4j.wait.WaitEngine;
import io.webagent4j.wait.WaitPolicy;
import io.webagent4j.wait.WaitResult;
import io.webagent4j.wait.WaitSample;
import java.time.Duration;

/**
 * Waits for a navigated page's DOM to stop changing, reusing {@code webagent4j-wait}'s generic
 * {@link WaitEngine} - the exact same primitive every locator, verification, and action wait in
 * this project already shares - rather than a crawler-specific {@code Thread.sleep} loop.
 *
 * <p>Each poll evaluates a small, deterministic JavaScript fingerprint through {@link
 * IPage#evaluate(String)} and reports it as both the probe's value and its {@code stabilityKey}.
 * {@link WaitPolicy#withStableFor} does the actual stability-window bookkeeping: the fingerprint
 * must read identically across consecutive polls spanning the configured window before the wait
 * reports success. This is not a network-idle signal - many modern pages maintain persistent
 * background network activity - it is a bounded, purely DOM-shape-based approximation, documented
 * as such in {@code docs/browser-crawler.md#stability}.
 *
 * <p>The fingerprint is {@code document.readyState}, the current element count, and - since a link
 * target can change without changing the element count at all (an {@code <a href>} attribute being
 * rewritten in place, common in SPA hydration) - a compact, order-stable digest of every anchor's
 * {@code href} attribute in document order, capped at the first {@value #HREF_DIGEST_LIMIT} anchors
 * so a page with an unusually large number of links still produces a small, cheap string rather
 * than an unbounded one. This still is not a general content-change detector (an anchor's visible
 * text or any non-anchor content changing without an accompanying element-count or href change is
 * not detected), and it still only ever reflects one observation point, never continued monitoring
 * after stability is accepted - see {@code docs/browser-crawler.md#stability} for the exact
 * contract.
 */
public final class PageStabilityWaiter {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final int HREF_DIGEST_LIMIT = 500;
    private static final String FINGERPRINT_SCRIPT =
            "document.readyState + ':' + document.querySelectorAll('*').length + ':' + "
                    + "Array.prototype.slice.call(document.querySelectorAll('a[href]'), 0, "
                    + HREF_DIGEST_LIMIT
                    + ").map(function(a) { return a.getAttribute('href'); }).join('|')";

    private final WaitEngine waitEngine;

    public PageStabilityWaiter(WaitEngine waitEngine) {
        this.waitEngine = waitEngine;
    }

    /** Waits for {@code page}'s DOM fingerprint to remain unchanged for {@code stabilityWindow}. */
    public WaitResult<String> awaitStable(IPage page, WaitBudget budget, Duration stabilityWindow) {
        WaitPolicy policy = WaitPolicy.pollingEvery(POLL_INTERVAL).withStableFor(stabilityWindow);
        IWaitProbe<String> probe =
                () -> {
                    String fingerprint = String.valueOf(page.evaluate(FINGERPRINT_SCRIPT));
                    return WaitSample.satisfied(fingerprint, fingerprint);
                };
        return waitEngine.await(budget, policy, probe);
    }
}
