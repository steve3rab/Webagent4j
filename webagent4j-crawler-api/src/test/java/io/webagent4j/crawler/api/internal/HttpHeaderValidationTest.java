package io.webagent4j.crawler.api.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Adversarial coverage for {@link HttpHeaderValidation} - the single, framework-owned rule every
 * caller-supplied crawler HTTP header is validated against, whichever internal transport eventually
 * sends the request. {@link CrawlRequestTest} and {@code HttpFetchModelsTest} (in {@code
 * webagent4j-crawler}) separately prove that both network-boundary objects actually invoke this
 * validator at construction time; these tests instead prove the validator's own rule is correct in
 * isolation, one case at a time.
 */
class HttpHeaderValidationTest {

    // --- HDR-001: representative valid headers remain accepted ------------------------------

    @Test
    void hdr001AcceptsRepresentativeValidHeaders() {
        assertThatCode(() -> HttpHeaderValidation.requireValidHeader("Accept", "text/html"))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                HttpHeaderValidation.requireValidHeader(
                                        "Accept-Language", "en-US,en;q=0.9"))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                HttpHeaderValidation.requireValidHeader(
                                        "User-Agent", "WebAgent4J-Crawler/0.1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> HttpHeaderValidation.requireValidHeader("X-Custom-Header", "value123"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAnEmptyHeaderValue() {
        assertThatCode(() -> HttpHeaderValidation.requireValidHeaderValue(""))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAHeaderValueContainingAHorizontalTab() {
        // The one piece of legacy HTTP field-value whitespace both java.net.http.HttpClient and
        // this project's own pinned transport already tolerate in a value.
        String valueWithTab = "value" + '\t' + "with-tab";
        assertThatCode(() -> HttpHeaderValidation.requireValidHeaderValue(valueWithTab))
                .doesNotThrowAnyException();
    }

    // --- HDR-002: empty header name -----------------------------------------------------------

    @Test
    void hdr002RejectsAnEmptyHeaderName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderName(""));
    }

    @Test
    void rejectsANullHeaderName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderName(null));
    }

    // --- HDR-003: whitespace in name -----------------------------------------------------------

    @Test
    void hdr003RejectsASpaceInTheHeaderName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderName("X-Bad Name"));
    }

    @Test
    void hdr003RejectsATabInTheHeaderName() {
        String nameWithTab = "X-Bad" + '\t' + "Name";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderName(nameWithTab));
    }

    @Test
    void rejectsAWhitespaceOnlyHeaderName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderName("   "));
    }

    // --- HDR-004: colon in name -----------------------------------------------------------------

    @Test
    void hdr004RejectsAColonInTheHeaderName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderName("X-Test:Injected"));
    }

    // --- HDR-005: CRLF in name -------------------------------------------------------------------

    @Test
    void hdr005RejectsACarriageReturnInTheHeaderName() {
        String nameWithCr = "X-Evil" + '\r' + "Name";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderName(nameWithCr));
    }

    @Test
    void hdr005RejectsALineFeedInTheHeaderName() {
        String nameWithLf = "X-Evil" + '\n' + "Name";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderName(nameWithLf));
    }

    @Test
    void hdr005RejectsAFullCrlfInjectionInTheHeaderName() {
        String nameWithCrlfInjection = "X-Evil" + "\r\n" + "X-Injected";
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> HttpHeaderValidation.requireValidHeaderName(nameWithCrlfInjection));
    }

    // --- HDR-006: CRLF in value, never echoed ----------------------------------------------------

    @Test
    void hdr006RejectsAClassicHeaderInjectionPayloadInTheValueAndNeverEchoesIt() {
        String injectionPayload = "value" + "\r\n" + "X-Injected: yes";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderValue(injectionPayload))
                .withMessageNotContaining(injectionPayload)
                .withMessageNotContaining("X-Injected");
    }

    // --- HDR-007 / HDR-008: isolated CR / isolated LF --------------------------------------------

    @Test
    void hdr007RejectsAnIsolatedCarriageReturnInTheValue() {
        String valueWithCr = "value" + '\r' + "more";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderValue(valueWithCr));
    }

    @Test
    void hdr008RejectsAnIsolatedLineFeedInTheValue() {
        String valueWithLf = "value" + '\n' + "more";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderValue(valueWithLf));
    }

    // --- HDR-009: NUL in name and value
    // -----------------------------------------------------------

    @Test
    void hdr009RejectsANulCharacterInTheHeaderName() {
        String nameWithNul = "X-Evil" + '\u0000' + "Name";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderName(nameWithNul));
    }

    @Test
    void hdr009RejectsANulCharacterInTheHeaderValue() {
        String valueWithNul = "value" + '\u0000' + "more";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderValue(valueWithNul));
    }

    // --- HDR-010: other forbidden control characters --------------------------------------------

    @Test
    void hdr010RejectsRepresentativeForbiddenControlCharactersInTheValue() {
        // C0 controls other than the legacy horizontal tab (0x09) must all be rejected - not just
        // CR/LF/NUL. 0x01 (SOH), 0x07 (BEL), and 0x1F (US) are representative of the range.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                HttpHeaderValidation.requireValidHeaderValue(
                                        "value" + '\u0001' + "more"));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                HttpHeaderValidation.requireValidHeaderValue(
                                        "value" + '\u0007' + "more"));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                HttpHeaderValidation.requireValidHeaderValue(
                                        "value" + '\u001F' + "more"));
    }

    @Test
    void hdr010RejectsDelInTheValue() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                HttpHeaderValidation.requireValidHeaderValue(
                                        "value" + '\u007F' + "more"));
    }

    // --- HDR-011: framework-controlled headers, case-insensitively
    // --------------------------------

    @Test
    void hdr011RejectsEveryFrameworkControlledHeaderCaseInsensitively() {
        for (String name : new String[] {"Host", "host", "HOST", "hOsT"}) {
            assertThatIllegalArgumentException()
                    .as("Host variant: %s", name)
                    .isThrownBy(() -> HttpHeaderValidation.requireNotFrameworkControlled(name));
        }
        for (String name : new String[] {"Connection", "connection", "CONNECTION"}) {
            assertThatIllegalArgumentException()
                    .as("Connection variant: %s", name)
                    .isThrownBy(() -> HttpHeaderValidation.requireNotFrameworkControlled(name));
        }
        for (String name : new String[] {"Content-Length", "content-length", "CONTENT-LENGTH"}) {
            assertThatIllegalArgumentException()
                    .as("Content-Length variant: %s", name)
                    .isThrownBy(() -> HttpHeaderValidation.requireNotFrameworkControlled(name));
        }
        for (String name :
                new String[] {"Transfer-Encoding", "transfer-encoding", "TRANSFER-ENCODING"}) {
            assertThatIllegalArgumentException()
                    .as("Transfer-Encoding variant: %s", name)
                    .isThrownBy(() -> HttpHeaderValidation.requireNotFrameworkControlled(name));
        }
        for (String name : new String[] {"Expect", "expect", "EXPECT"}) {
            assertThatIllegalArgumentException()
                    .as("Expect variant: %s", name)
                    .isThrownBy(() -> HttpHeaderValidation.requireNotFrameworkControlled(name));
        }
        for (String name : new String[] {"Upgrade", "upgrade", "UPGRADE"}) {
            assertThatIllegalArgumentException()
                    .as("Upgrade variant: %s", name)
                    .isThrownBy(() -> HttpHeaderValidation.requireNotFrameworkControlled(name));
        }
    }

    @Test
    void frameworkControlledCheckNeverRejectsAnUnrelatedHeaderName() {
        assertThatCode(() -> HttpHeaderValidation.requireNotFrameworkControlled("X-Custom"))
                .doesNotThrowAnyException();
        assertThatCode(() -> HttpHeaderValidation.requireNotFrameworkControlled("Accept"))
                .doesNotThrowAnyException();
        assertThatCode(() -> HttpHeaderValidation.requireNotFrameworkControlled("User-Agent"))
                .doesNotThrowAnyException();
    }

    // --- URL-DIAG-004 equivalent: a malformed header value's secret marker is never echoed
    // --------

    @Test
    void aSecretMarkerInAMalformedHeaderValueIsNeverEchoedInTheExceptionMessage() {
        String diagnosticSentinel = "DIAGNOSTIC_SENTINEL_471182";
        String malformedValue = diagnosticSentinel + "\r\n" + "X-Injected: yes";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderValue(malformedValue))
                .withMessageNotContaining(diagnosticSentinel);
    }

    @Test
    void aSecretMarkerInAMalformedHeaderNameIsNeverEchoedInTheExceptionMessage() {
        String diagnosticSentinel = "DIAGNOSTIC_SENTINEL_918273";
        String malformedName = diagnosticSentinel + ":bad";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaderName(malformedName))
                .withMessageNotContaining(diagnosticSentinel);
    }

    // --- determinism: first-failure ordering
    // ------------------------------------------------------

    @Test
    void requireValidHeadersFailsOnTheFirstInvalidEntryInIterationOrder() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Ok-First", "fine");
        headers.put("X-Bad-Second", "value" + "\r\n" + "injected");
        headers.put("X-Bad-Third", "value" + '\u0000' + "more");

        // The second entry (in insertion order) is the first invalid one - a LinkedHashMap
        // iterates in that exact order, so the failure must come from validating it, not the
        // third entry, which is also invalid but must never be reached first.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpHeaderValidation.requireValidHeaders(headers))
                .withMessageContaining("header value contains a character forbidden");
    }

    @Test
    void requireValidHeadersAcceptsAnEmptyMap() {
        assertThatCode(() -> HttpHeaderValidation.requireValidHeaders(Map.of()))
                .doesNotThrowAnyException();
    }
}
