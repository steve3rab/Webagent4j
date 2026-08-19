package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.browser.IFrame;
import io.webagent4j.extraction.api.ExtractedTable;
import io.webagent4j.extraction.api.ExtractionAttributeMissingException;
import io.webagent4j.extraction.api.ExtractionConversionException;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.extraction.api.IValueConverter;
import io.webagent4j.locator.api.LocatorDefinition;
import org.junit.jupiter.api.Test;

/**
 * Adversarial extraction scenarios: two regions sharing the same structure but different scopes, a
 * sibling frame carrying the same data under the same name, an element replaced before a bounded
 * read, and malformed/incomplete data (empty and ragged tables, a missing attribute, an unparsable
 * number). None of these may silently produce a value from the wrong scope, a padded-out row, or a
 * quietly substituted default.
 */
class ExtractionRobustnessIT {

    /** EXTRACT-ROBUST-001: two sections sharing the same structure never leak across scope. */
    @Test
    void neverLeaksAValueFromTheWrongScopedSection() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/two-scoped-sections")) {
            ExtractionResult<String> fromA =
                    page.extract(
                            ExtractionRequest.text(LocatorDefinition.css("#product-a .price")));
            ExtractionResult<String> fromB =
                    page.extract(
                            ExtractionRequest.text(LocatorDefinition.css("#product-b .price")));

            assertThat(fromA.value()).isEqualTo("10");
            assertThat(fromB.value()).isEqualTo("20");
        }
    }

    /** EXTRACT-ROBUST-002: a sibling frame carrying the same identity never leaks its data. */
    @Test
    void neverLeaksAValueFromASiblingFrame() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/iframe-siblings")) {
            IFrame productA = page.frame().named("product-a").single();
            IFrame productB = page.frame().named("product-b").single();

            ExtractionResult<String> fromA =
                    productA.extract(
                            ExtractionRequest.text(LocatorDefinition.element().withId("amount")));
            ExtractionResult<String> fromB =
                    productB.extract(
                            ExtractionRequest.text(LocatorDefinition.element().withId("amount")));

            assertThat(fromA.value()).isEqualTo("111 USD");
            assertThat(fromB.value()).isEqualTo("222 USD");
        }
    }

    /**
     * EXTRACT-ROBUST-003: an element replaced by another with the same semantic identity before
     * extraction begins is read fresh - never a value cached from before the replacement.
     */
    @Test
    void readsTheReplacementElementNeverAStaleCachedOne() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/element-replaced-during-wait")) {
            page.evaluate("replaceAmount()");

            ExtractionResult<String> result =
                    page.extract(
                            ExtractionRequest.text(LocatorDefinition.element().withId("amount")));

            assertThat(result.value()).isEqualTo("99 USD");
        }
    }

    /** EXTRACT-ROBUST-004: an empty table reports its headers with zero rows, not an error. */
    @Test
    void anEmptyTableReportsHeadersWithNoRows() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/empty-table")) {
            ExtractionResult<ExtractedTable> result =
                    page.extractTable(LocatorDefinition.css("table"));

            assertThat(result.value().headers()).containsExactly("Name");
            assertThat(result.value().rows()).isEmpty();
        }
    }

    /**
     * EXTRACT-ROBUST-005: a row with fewer cells than the header count is reported exactly as found
     * - never silently padded with empty cells to match the header width.
     */
    @Test
    void aRaggedRowIsReportedExactlyRatherThanPaddedToTheHeaderWidth() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/ragged-table")) {
            ExtractionResult<ExtractedTable> result =
                    page.extractTable(LocatorDefinition.css("table"));

            ExtractedTable table = result.value();
            assertThat(table.headers()).hasSize(3);
            assertThat(table.rows().get(0).size()).isEqualTo(2);
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> table.cell(0, 2));
        }
    }

    /**
     * EXTRACT-ROBUST-006: a missing attribute on an existing element is distinguished, not empty.
     */
    @Test
    void aMissingAttributeOnAnExistingElementIsNeverSilentlyEmpty() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/missing-attribute")) {
            assertThatExceptionOfType(ExtractionAttributeMissingException.class)
                    .isThrownBy(
                            () ->
                                    page.extract(
                                            ExtractionRequest.attribute(
                                                    LocatorDefinition.element()
                                                            .withId("product-link"),
                                                    "href")))
                    .satisfies(failure -> assertThat(failure.attributeName()).isEqualTo("href"));
        }
    }

    /** EXTRACT-ROBUST-007: an unparsable number never silently becomes zero or null. */
    @Test
    void anUnparsableNumberFailsConversionRatherThanBecomingAQuietDefault() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/invalid-number")) {
            assertThatExceptionOfType(ExtractionConversionException.class)
                    .isThrownBy(
                            () ->
                                    page.extract(
                                            ExtractionRequest.text(
                                                            LocatorDefinition.element()
                                                                    .withId("amount"))
                                                    .convert(IValueConverter.toInteger())))
                    .satisfies(failure -> assertThat(failure.rawValue()).isEqualTo("not a number"));
        }
    }
}
