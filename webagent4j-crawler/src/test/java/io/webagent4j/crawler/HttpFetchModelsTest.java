package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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

    // --- HDR-013: direct construction of HttpFetchRequest cannot bypass canonical validation ----

    @Test
    void hdr013DirectConstructionRejectsAMalformedHeaderNameContainingAColon() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new HttpFetchRequest(
                                        URI_VALUE,
                                        Duration.ofSeconds(1),
                                        Map.of("X-Test:Injected", "value"),
                                        1));
    }

    @Test
    void hdr013DirectConstructionRejectsACrlfInjectionPayloadInAHeaderValue() {
        String injectionPayload = "value" + "\r\n" + "X-Injected: yes";
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new HttpFetchRequest(
                                        URI_VALUE,
                                        Duration.ofSeconds(1),
                                        Map.of("X-Evil", injectionPayload),
                                        1));
    }

    @Test
    void hdr013DirectConstructionRejectsAFrameworkControlledHeader() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new HttpFetchRequest(
                                        URI_VALUE,
                                        Duration.ofSeconds(1),
                                        Map.of("Host", "evil.example.test"),
                                        1));
    }

    @Test
    void hdr013DirectConstructionAcceptsAWellFormedHeader() {
        HttpFetchRequest request =
                new HttpFetchRequest(
                        URI_VALUE, Duration.ofSeconds(1), Map.of("X-Custom", "value123"), 1);

        assertThat(request.headers()).containsEntry("X-Custom", "value123");
    }

    // --- URL-DIAG-004 equivalent: a malformed header value's secret marker is never echoed ------

    @Test
    void aSecretMarkerInAMalformedHeaderValueIsNeverEchoedByHttpFetchRequest() {
        String diagnosticSentinel = "DIAGNOSTIC_SENTINEL_449173";
        String malformedValue = diagnosticSentinel + "\r\n" + "X-Injected: yes";
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new HttpFetchRequest(
                                        URI_VALUE,
                                        Duration.ofSeconds(1),
                                        Map.of("X-Evil", malformedValue),
                                        1))
                .withMessageNotContaining(diagnosticSentinel);
    }

    // --- determinism -----------------------------------------------------------------------------

    @Test
    void constructingWithNoHeadersNeverThrows() {
        assertThatCode(() -> new HttpFetchRequest(URI_VALUE, Duration.ofSeconds(1), Map.of(), 1))
                .doesNotThrowAnyException();
    }
}
