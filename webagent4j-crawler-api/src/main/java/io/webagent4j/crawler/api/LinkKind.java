package io.webagent4j.crawler.api;

/**
 * Which HTML element produced a {@link DiscoveredLink}. Not the HTML {@code rel} attribute: two
 * links of the same {@code kind} can carry different, or no, {@code rel} values.
 */
public enum LinkKind {

    /** A navigation link from an {@code <a href>} element. */
    ANCHOR,

    /** A navigation link from an {@code <area href>} element inside an image map. */
    AREA
}
