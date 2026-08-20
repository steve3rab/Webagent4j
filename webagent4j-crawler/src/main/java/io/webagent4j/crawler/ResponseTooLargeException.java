package io.webagent4j.crawler;

import java.io.IOException;
import java.net.URI;

/** A response body exceeded its request's {@code maxResponseBytes} while being read. */
public final class ResponseTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient URI uri;
    private final long limit;

    /** Creates an exception reporting that {@code uri}'s response exceeded {@code limit} bytes. */
    public ResponseTooLargeException(URI uri, long limit) {
        super("Response body for " + uri + " exceeded the " + limit + " byte limit");
        this.uri = uri;
        this.limit = limit;
    }

    /** Returns the URL whose response was too large. */
    public URI uri() {
        return uri;
    }

    /** Returns the byte limit that was exceeded. */
    public long limit() {
        return limit;
    }
}
