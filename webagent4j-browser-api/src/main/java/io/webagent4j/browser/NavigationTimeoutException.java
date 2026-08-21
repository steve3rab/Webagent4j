package io.webagent4j.browser;

import io.webagent4j.common.WebAgentException;
import java.time.Duration;

/**
 * Indicates that a {@link IPage#navigate(String, Duration)} call did not commit within the
 * caller-supplied timeout.
 *
 * <p>Backend-neutral by design: a caller such as {@code BrowserCrawler} can classify a navigation
 * timeout deterministically - by catching this type - without depending on any backend
 * implementation or parsing a backend-specific exception message. The original backend failure, if
 * one exists, is preserved as {@link #getCause()}.
 */
public final class NavigationTimeoutException extends WebAgentException {

    private static final long serialVersionUID = 1L;

    /** Creates a navigation timeout exception with a diagnostic message. */
    public NavigationTimeoutException(String message) {
        super(message);
    }

    /** Creates a navigation timeout exception, preserving the backend's original failure. */
    public NavigationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
