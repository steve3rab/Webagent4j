package io.webagent4j.core;

/** Lightweight public facade for creating WebAgent4J components. */
public final class WebAgent {

    /** Current API version. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    private WebAgent() {}

    /** Starts a browser launch builder without loading any backend until {@code launch()}. */
    public static BrowserBuilder browser() {
        return new BrowserBuilder();
    }
}
