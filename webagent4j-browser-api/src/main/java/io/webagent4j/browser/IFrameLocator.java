package io.webagent4j.browser;

import io.webagent4j.locator.api.TextMatch;
import java.time.Duration;
import java.util.Optional;

/**
 * Fluent immutable query that resolves a frame only at a terminal operation.
 *
 * <p>Unlike {@link io.webagent4j.locator.api.ILocator} at the element level, this contract exposes
 * only {@link #single()} and {@link #tryFind()} - no {@code first()} and no {@code all()}. A frame
 * must be resolved by an unambiguous semantic identity, never by document order or position: there
 * is no scoring dimension to rank frame candidates by, so a "highest ranked" pick would really mean
 * "first in DOM order", exactly the hidden tie breaker this codebase deliberately never uses for
 * frames. An {@code all()} enumerating several matches would also be misleading here, since every
 * {@code IFrame} it returned would carry the identical query criteria - not a distinct,
 * individually usable identity for "the second one" versus "the first one" - so it is not offered
 * at all rather than offered with a trap. Two or more equally valid matches are always ambiguous,
 * never silently narrowed to "the first one".
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

    /** Returns one unambiguous frame or fails for zero or multiple equally valid matches. */
    IFrame single();

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
