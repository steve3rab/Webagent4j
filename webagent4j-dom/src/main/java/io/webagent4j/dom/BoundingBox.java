package io.webagent4j.dom;

/**
 * Immutable element coordinates in CSS pixels.
 *
 * @param x horizontal origin
 * @param y vertical origin
 * @param width rendered width
 * @param height rendered height
 */
public record BoundingBox(double x, double y, double width, double height) {

    /** Validates finite coordinates and non-negative finite dimensions. */
    public BoundingBox {
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(width)
                || !Double.isFinite(height)) {
            throw new IllegalArgumentException("bounding box values must be finite");
        }
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("bounding box dimensions cannot be negative");
        }
    }
}
