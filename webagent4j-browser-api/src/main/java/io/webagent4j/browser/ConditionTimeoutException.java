package io.webagent4j.browser;

import io.webagent4j.common.WebAgentException;
import java.time.Duration;

/**
 * Indicates that a {@link IPage#waitForCondition(String, Duration)} call's condition did not become
 * satisfied within the caller-supplied timeout.
 *
 * <p>Not necessarily "the expression was evaluated and found falsy every time": how a backend
 * handles a document replacement occurring mid-wait (a client-side navigation, for example) is
 * backend-specific - see the Playwright adapter's own Javadoc for what is a guaranteed bound versus
 * an observed, version-specific behavior there. A backend may never observe a single evaluation
 * definitively fail, yet still report the condition was not satisfied in time from the caller's
 * point of view. This type reports exactly that outward-observable fact, nothing more specific.
 *
 * <p>Backend-neutral by design: a caller such as {@code BrowserCrawler}'s stability wait can
 * classify a condition timeout deterministically - by catching this type - without depending on any
 * backend implementation or parsing a backend-specific exception message. The original backend
 * failure, if one exists, is preserved as {@link #getCause()} - though a cause is not guaranteed:
 * see {@link IPage#waitForCondition(String, Duration)}'s contract for the case where this exception
 * is raised before any backend call was even attempted (an already-exhausted shared budget), which
 * carries no backend cause to preserve.
 */
public final class ConditionTimeoutException extends WebAgentException {

    private static final long serialVersionUID = 1L;

    /** Creates a condition timeout exception with a diagnostic message. */
    public ConditionTimeoutException(String message) {
        super(message);
    }

    /** Creates a condition timeout exception, preserving the backend's original failure. */
    public ConditionTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
