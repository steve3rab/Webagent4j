package io.webagent4j.browsercrawler;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A cooperative, thread-safe cancellation signal for a single {@link BrowserCrawlRequest}.
 *
 * <p>No cancellation abstraction exists elsewhere in WebAgent4J (audited across {@code
 * webagent4j-common} and every other module before adding this type); this is intentionally the
 * smallest possible primitive rather than a general-purpose framework concept. A caller creates one
 * token per crawl, passes it to {@link
 * BrowserCrawlRequest.Builder#cancellationToken(CancellationToken)}, and calls {@link #cancel()}
 * from any thread - typically not the thread running {@code crawl(...)} itself, since that call
 * blocks until the crawl reaches a cancellation checkpoint.
 *
 * <p>Cancellation is cooperative, not forceful: an in-flight page navigation is never forcibly
 * aborted (the backend-neutral browser API exposes no such operation). Once observed, no new
 * navigation is claimed; already-claimed, in-flight navigations are allowed to finish so their
 * results remain part of the deterministic output rather than being discarded mid-flight.
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private CancellationToken() {}

    /** Creates a fresh, not-yet-cancelled token. */
    public static CancellationToken create() {
        return new CancellationToken();
    }

    /** Requests cancellation. Idempotent - calling this more than once has no additional effect. */
    public void cancel() {
        cancelled.set(true);
    }

    /** Returns {@code true} once {@link #cancel()} has been called at least once. */
    public boolean isCancelled() {
        return cancelled.get();
    }
}
