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
 * <p>The fingerprint is four parts, concatenated: {@code document.readyState}; the total element
 * count; the total count of {@code href}-bearing anchors (so an anchor being added or removed past
 * the digest boundary below still changes the fingerprint, even though its own href is not
 * individually captured); and a bounded, order-stable, structurally unambiguous digest of the first
 * {@value #HREF_DIGEST_LIMIT} anchors' {@code href} attributes - {@code JSON.stringify} of a JSON
 * array, not a delimiter-joined string, so an href that itself happens to contain the delimiter
 * cannot collide with a genuinely different href sequence. {@value #HREF_DIGEST_LIMIT} is not an
 * arbitrary lower cap: it is exactly {@link io.webagent4j.observation.ObservationOptions}'s {@code
 * maxElements} bound this engine's own discovery observation uses (see {@code BrowserCrawler}), so
 * a link mutation stability can ever be asked to notice is, by construction, exactly the same set
 * of links discovery will actually see - a mutation past that boundary is invisible to both,
 * consistently, rather than visible to one and not the other.
 *
 * <p>This still is not a general content-change detector (an anchor's visible text, a non-anchor
 * element's content, or an href change past the digest boundary is not detected), and it still only
 * ever reflects one observation point, never continued monitoring after stability is accepted - see
 * {@code docs/browser-crawler.md#stability} for the exact contract.
 */
public final class PageStabilityWaiter {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /** Matches {@code ObservationOptions}'s {@code maxElements} default - see the class Javadoc. */
    private static final int HREF_DIGEST_LIMIT = 2000;

    private static final String FINGERPRINT_SCRIPT =
            "document.readyState + ':' + document.querySelectorAll('*').length + ':' + "
                    + "document.querySelectorAll('a[href]').length + ':' + "
                    + "JSON.stringify(Array.prototype.slice.call("
                    + "document.querySelectorAll('a[href]'), 0, "
                    + HREF_DIGEST_LIMIT
                    + ").map(function(a) { return a.getAttribute('href'); }))";

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
