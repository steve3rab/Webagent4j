package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpFetchModelsTest {

    private static final URI URI_VALUE = URI.create("https://example.test/");

    @Test
    void requestTimeoutMustBePositive() {
        assertThatThrownBy(() -> new HttpFetchRequest(URI_VALUE, Duration.ZERO, Map.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpFetchRequest(URI_VALUE, Duration.ofNanos(-1), Map.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void responseElapsedTimeCannotBeNegative() {
        assertThatThrownBy(
                        () ->
                                new HttpFetchResult(
                                        URI_VALUE,
                                        200,
                                        Map.of(),
                                        new byte[0],
                                        "text/plain",
                                        Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
