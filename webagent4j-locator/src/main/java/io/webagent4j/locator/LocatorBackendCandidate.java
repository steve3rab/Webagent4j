package io.webagent4j.locator;

import io.webagent4j.dom.IElement;
import java.util.Objects;

/**
 * Backend result with an opaque identity used to merge repeated semantic discoveries.
 *
 * @param identity stable identity for the current DOM state
 * @param element live backend-neutral element
 * @param domOrder zero-based DOM order supplied by the backend
 */
public record LocatorBackendCandidate(String identity, IElement element, int domOrder) {

    /** Validates candidate data. */
    public LocatorBackendCandidate {
        identity = Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(element, "element");
        if (domOrder < 0) {
            throw new IllegalArgumentException("domOrder cannot be negative");
        }
    }
}
