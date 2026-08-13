package io.webagent4j.observation;

/** Immutable viewport dimensions in CSS pixels. */
public record ViewportSize(int width, int height) {

    /** Rejects negative dimensions while allowing an unknown zero dimension. */
    public ViewportSize {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("viewport dimensions cannot be negative");
        }
    }
}
