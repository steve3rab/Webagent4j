package io.webagent4j.common;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Classifies a resolution failure using the typed {@link ILocatorFailure} contract instead of
 * exception class names or message text.
 *
 * <p>A failure is inspected through a bounded, cycle-safe cause chain so that a locator failure
 * wrapped by an unrelated {@code RuntimeException} is still recognized. A failure with no typed
 * {@link ILocatorFailure} anywhere in that bounded chain is never treated as a safe "not found" or
 * "ambiguous" outcome; callers must classify it as an opaque backend or runtime failure instead.
 */
public final class LocatorFailureClassifier {

    /** Maximum number of cause hops inspected before giving up on finding a typed failure. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private LocatorFailureClassifier() {
        // not instantiable
    }

    /**
     * Returns true only when a typed {@link ILocatorFailure} is found within the bounded cause
     * chain and reports a safe "not found" outcome.
     */
    public static boolean isNotFound(Throwable failure) {
        ILocatorFailure typed = unwrap(failure);
        return typed != null && typed.isNotFound();
    }

    /**
     * Returns true only when a typed {@link ILocatorFailure} is found within the bounded cause
     * chain and reports a safe "ambiguous" outcome.
     */
    public static boolean isAmbiguous(Throwable failure) {
        ILocatorFailure typed = unwrap(failure);
        return typed != null && typed.isAmbiguous();
    }

    private static ILocatorFailure unwrap(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            if (current instanceof ILocatorFailure typed) {
                return typed;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }
}
