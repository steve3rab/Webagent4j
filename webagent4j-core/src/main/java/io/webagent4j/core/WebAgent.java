package io.webagent4j.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Lightweight public facade for creating WebAgent4J components. */
public final class WebAgent {

    /** Current API version. */
    public static final String VERSION = loadVersion();

    private WebAgent() {}

    private static String loadVersion() {
        final String resource = "/io/webagent4j/core/webagent4j-version.properties";

        try (InputStream input = WebAgent.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing WebAgent4J version resource: " + resource);
            }

            Properties properties = new Properties();
            properties.load(input);

            String version = properties.getProperty("version");

            if (version == null || version.isBlank()) {
                throw new IllegalStateException(
                        "Missing or blank 'version' property in " + resource);
            }

            version = version.trim();

            if (version.contains("${")) {
                throw new IllegalStateException(
                        "Unresolved Maven version placeholder in " + resource + ": " + version);
            }

            return version;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to load WebAgent4J version from " + resource, exception);
        }
    }

    /** Starts a browser launch builder without loading any backend until {@code launch()}. */
    public static BrowserBuilder browser() {
        return new BrowserBuilder();
    }
}
