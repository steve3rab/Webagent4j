package io.webagent4j.browser.playwright;

import com.microsoft.playwright.PlaywrightException;

/** Classifies the narrow Playwright failures that carry stable disappearance semantics. */
final class PlaywrightFailureClassifier {

    private static final String FRAME_DETACHED = "Frame was detached";
    private static final String PROTOCOL_FRAME_DETACHED =
            "Error {\n  message='" + FRAME_DETACHED + "\n";

    private PlaywrightFailureClassifier() {}

    /**
     * Returns whether Playwright explicitly reported that the frame owning an operation was
     * detached.
     *
     * <p>Playwright Java 1.60 exposes this protocol condition as a plain {@link
     * PlaywrightException}, without a dedicated subtype or structured error code. Matching is
     * therefore deliberately limited to either the bare canonical message or the canonical first
     * field of Playwright's protocol error envelope. Incidental mentions elsewhere in an opaque
     * backend error do not qualify.
     */
    static boolean isFrameDetached(PlaywrightException failure) {
        String message = failure.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.replace("\r\n", "\n");
        return normalized.equals(FRAME_DETACHED) || normalized.startsWith(PROTOCOL_FRAME_DETACHED);
    }
}
