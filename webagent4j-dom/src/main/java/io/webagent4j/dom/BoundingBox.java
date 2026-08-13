package io.webagent4j.dom;

/**
 * Immutable element coordinates in CSS pixels.
 *
 * @param x horizontal origin
 * @param y vertical origin
 * @param width rendered width
 * @param height rendered height
 */
public record BoundingBox(double x, double y, double width, double height) {}
