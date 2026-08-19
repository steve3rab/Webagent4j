package io.webagent4j.browser;

import io.webagent4j.locator.api.TextMatch;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Fluent immutable query that resolves a frame only at a terminal operation.
 *
 * <p>Mirrors {@link io.webagent4j.locator.api.ILocator}'s contract at the frame level: {@link
 * #single()} requires the match to be unique, {@link #first()} accepts multiple candidates and
 * returns the highest-ranked one in deterministic order, and {@link #all()} returns every
 * candidate. There is no scoring or DOM-order tie breaker for frames - two or more equally valid
 * matches are always ambiguous, never silently narrowed to "the first one".
 */
public interface IFrameLocator {

    /** Constrains the query to an exact element id. */
    IFrameLocator withId(String id);

    /** Constrains the query to an exact, case-insensitive HTML {@code name} attribute. */
    IFrameLocator named(String name);

    /** Constrains the query to an exact, case-insensitive {@code title} attribute. */
    IFrameLocator withTitle(String title);

    /** Constrains the query to the supplied URL criterion. */
    IFrameLocator withUrl(TextMatch match);

    /** Overrides the maximum time allowed for resolution. */
    IFrameLocator timeout(Duration timeout);

    /**
     * Requires the resolved frame's identity to remain stable continuously for the supplied
     * positive duration before resolution succeeds - the same stability guarantee {@link
     * io.webagent4j.locator.api.ILocator#stableFor(Duration)} gives at the element level, applied
     * to the frame boundary itself. A frame that becomes ambiguous or disappears during that window
     * fails the wait rather than silently returning whichever candidate satisfied the very first
     * poll.
     */
    IFrameLocator stableFor(Duration duration);

    /** Returns the highest-ranked compatible frame or fails when none exists. */
    IFrame first();

    /** Returns one unambiguous frame or fails for zero or multiple equally valid matches. */
    IFrame single();

    /** Returns every compatible frame currently present, in deterministic document order. */
    List<IFrame> all();

    /**
     * Attempts a single unambiguous resolution without converting a real failure into a silent
     * empty result.
     *
     * <p>Returns an empty optional only when the underlying failure is a typed "not found" outcome.
     * An ambiguous match set still raises the normal explicit exception, and a backend or runtime
     * failure is always rethrown.
     */
    Optional<IFrame> tryFind();
}
