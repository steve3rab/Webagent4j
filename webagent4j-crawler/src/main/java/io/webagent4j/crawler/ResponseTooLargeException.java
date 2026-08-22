package io.webagent4j.crawler;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.net.URI;
import java.util.Objects;

/**
 * A response body exceeded its request's {@code maxResponseBytes} while being read.
 *
 * <p>The exception message omits the URL because it may contain sensitive query data. Java native
 * serialization is explicitly unsupported because {@link #uri()} is required structured state.
 */
public final class ResponseTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient URI uri;
    private final long limit;

    /** Creates an exception reporting that {@code uri}'s response exceeded {@code limit} bytes. */
    public ResponseTooLargeException(URI uri, long limit) {
        super("Response body exceeded the " + limit + " byte limit");
        this.uri = Objects.requireNonNull(uri, "uri");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
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

    @Serial
    private void writeObject(ObjectOutputStream ignored) throws IOException {
        throw new NotSerializableException(ResponseTooLargeException.class.getName());
    }

    @Serial
    private void readObject(ObjectInputStream ignored) throws IOException, ClassNotFoundException {
        throw new NotSerializableException(ResponseTooLargeException.class.getName());
    }
}
