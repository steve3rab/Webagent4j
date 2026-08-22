package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ResponseTooLargeExceptionTest {

    @Test
    void messageDoesNotExposeTheRequestUrl() {
        URI sensitiveUrl = URI.create("https://example.test/?token=secret-value");

        ResponseTooLargeException failure = new ResponseTooLargeException(sensitiveUrl, 1024);

        assertThat(failure.getMessage()).doesNotContain(sensitiveUrl.toString());
        assertThat(failure.uri()).isEqualTo(sensitiveUrl);
        assertThat(failure.limit()).isEqualTo(1024);
    }

    @Test
    void structuredExceptionRejectsJavaNativeSerialization() {
        ResponseTooLargeException failure =
                new ResponseTooLargeException(URI.create("https://example.test/"), 1024);

        assertThatThrownBy(() -> serialize(failure)).isInstanceOf(NotSerializableException.class);
    }

    private static void serialize(Object value) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
    }
}
