package io.webagent4j.core;

/** Lightweight public facade for creating WebAgent4J components. */
public final class WebAgent {

    /** Current API version. */
    public static final String VERSION = "1.0.0-RC1";

    private WebAgent() {}

    /** Starts a browser launch builder without loading any backend until {@code launch()}. */
    public static BrowserBuilder browser() {
        return new BrowserBuilder();
    }
}
