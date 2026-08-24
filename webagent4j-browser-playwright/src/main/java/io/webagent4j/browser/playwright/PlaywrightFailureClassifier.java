package io.webagent4j.browser.playwright;

import com.microsoft.playwright.PlaywrightException;

/** Classifies the narrow Playwright failures that carry stable disappearance semantics. */
final class PlaywrightFailureClassifier {

    private static final String FRAME_DETACHED = "Frame was detached";
    private static final String PROTOCOL_FRAME_DETACHED =
            "Error {\n  message='" + FRAME_DETACHED + "\n";
    private static final String FRAME_MISSING_FOR_SELECTOR = "Failed to find frame for selector \"";
    private static final String PROTOCOL_FRAME_MISSING_FOR_SELECTOR =
            "Error {\n  message='" + FRAME_MISSING_FOR_SELECTOR;
    private static final String DIFFERENT_DOCUMENT_ADOPTION =
            "Unable to adopt element handle from a different document";
    private static final String PROTOCOL_DIFFERENT_DOCUMENT_ADOPTION =
            "Error {\n  message='" + DIFFERENT_DOCUMENT_ADOPTION + "\n";

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

    /**
     * Returns whether Playwright definitively reported that the frame required to resolve a lazy
     * selector no longer exists.
     *
     * <p>The selector suffix is intentionally not matched in arbitrary text. Only Playwright's bare
     * canonical message or the first protocol-envelope field qualifies, so a disconnected browser
     * error that merely mentions the phrase remains an opaque backend failure.
     */
    static boolean isFrameUnavailable(PlaywrightException failure) {
        if (isFrameDetached(failure)) {
            return true;
        }
        String message = failure.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.replace("\r\n", "\n");
        return normalized.startsWith(FRAME_MISSING_FOR_SELECTOR)
                || normalized.startsWith(PROTOCOL_FRAME_MISSING_FOR_SELECTOR);
    }

    /**
     * Returns whether Playwright reported the narrow cross-document adoption race produced when a
     * document changes between selector resolution and handle adoption.
     *
     * <p>This signal is <strong>not</strong> absence by itself. Callers must perform a fresh
     * synchronous recheck and may convert it to absence only when that recheck proves zero matches
     * or reports a canonical unavailable-frame condition.
     */
    static boolean isDifferentDocumentAdoptionRace(PlaywrightException failure) {
        String message = failure.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.replace("\r\n", "\n");
        return normalized.equals(DIFFERENT_DOCUMENT_ADOPTION)
                || normalized.startsWith(PROTOCOL_DIFFERENT_DOCUMENT_ADOPTION);
    }
}
