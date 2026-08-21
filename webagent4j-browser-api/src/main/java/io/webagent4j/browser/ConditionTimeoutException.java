package io.webagent4j.browser;

import io.webagent4j.common.WebAgentException;
import java.time.Duration;

/**
 * Indicates that a {@link IPage#waitForCondition(String, Duration)} call's expression never
 * returned a truthy value within the caller-supplied timeout.
 *
 * <p>Backend-neutral by design: a caller such as {@code BrowserCrawler}'s stability wait can
 * classify a condition timeout deterministically - by catching this type - without depending on any
 * backend implementation or parsing a backend-specific exception message. The original backend
 * failure, if one exists, is preserved as {@link #getCause()}.
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
