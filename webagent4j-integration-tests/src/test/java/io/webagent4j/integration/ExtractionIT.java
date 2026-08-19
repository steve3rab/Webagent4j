package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.webagent4j.browser.IFrame;
import io.webagent4j.browser.IPage;
import io.webagent4j.extraction.api.ExtractedTable;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.extraction.api.IValueConverter;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Real-browser scenarios for {@link ExtractionEngine} through {@link IPage#extract}/{@link
 * IFrame#extract} - proving the engine resolves against the real DOM through the existing locator
 * engine, not a parallel resolution path, and that frame-scoped extraction shares the exact same
 * live re-resolution guarantees {@code IFrame#locate} already has.
 */
class ExtractionIT {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(300);

    /** EXT-001: simple text extraction. */
    @Test
    void extractsSimpleText() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/simple-text")) {
            ExtractionResult<String> result =
                    page.extract(
                            ExtractionRequest.text(LocatorDefinition.element().withId("amount")));

            assertThat(result.value()).isEqualTo("42 USD");
            assertThat(result.rawValue()).contains("42 USD");
        }
    }

    /**
     * EXT-002: Unicode text survives extraction intact - TEXT extraction is exactly whatever {@code
     * IElement#text()} already returns (including its own existing whitespace normalization), never
     * a second, independent normalizer applied by extraction itself. This deliberately asserts with
     * {@code contains} rather than an exact match: {@code IElement#text()}'s Java-side whitespace
     * collapsing does not treat a non-breaking space as ordinary whitespace the way an accessible-
     * name normalizer would, so an exact-match assertion here would encode that low-level detail
     * rather than the actual extraction contract under test.
     */
    @Test
    void extractsUnicodeTextUsingIElementTextsOwnNormalization() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/unicode-text")) {
            ExtractionResult<String> result =
                    page.extract(
                            ExtractionRequest.text(LocatorDefinition.element().withId("title")));

            assertThat(result.value()).contains("Café").contains("München");
        }
    }

    /** EXT-003: attribute extraction. */
    @Test
    void extractsAnAttribute() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/attribute")) {
            ExtractionResult<String> result =
                    page.extract(
                            ExtractionRequest.attribute(
                                    LocatorDefinition.element().withId("product-link"), "href"));

            assertThat(result.value()).isEqualTo("/products/laptop-b");
        }
    }

    /** EXT-004: a dynamically changed input value is read live, not the initial HTML attribute. */
    @Test
    void extractsTheLiveFormValueAfterItChanges() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/dynamic-value")) {
            page.evaluate("setQuantity()");

            ExtractionResult<String> result =
                    page.extract(
                            ExtractionRequest.value(
                                    LocatorDefinition.element().withId("quantity")));

            assertThat(result.value()).isEqualTo("5");
        }
    }

    /** EXT-005: list extraction preserves DOM order. */
    @Test
    void extractsAListInDomOrder() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/list")) {
            ExtractionResult<List<String>> result =
                    page.extractList(ExtractionRequest.text(LocatorDefinition.css("ul li")));

            assertThat(result.value()).containsExactly("Laptop B", "Mouse", "Keyboard");
        }
    }

    /** EXT-006: table extraction reads headers and rows from a real HTML table. */
    @Test
    void extractsATable() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/table")) {
            ExtractionResult<ExtractedTable> result =
                    page.extractTable(LocatorDefinition.css("table"));

            ExtractedTable table = result.value();
            assertThat(table.headers()).containsExactly("Name", "Price");
            assertThat(table.cell(0, "Name")).contains("Laptop B");
            assertThat(table.cell(0, "Price")).contains("999");
            assertThat(table.cell(1, "Name")).contains("Mouse");
        }
    }

    /**
     * EXT-007: a missing source raises the normal LocatorNotFoundException, never silently empty.
     */
    @Test
    void aMissingSourceRaisesLocatorNotFoundException() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/missing-source")) {
            assertThatExceptionOfType(LocatorNotFoundException.class)
                    .isThrownBy(
                            () ->
                                    page.extract(
                                            ExtractionRequest.text(
                                                    LocatorDefinition.element()
                                                            .withId("nonexistent")
                                                            .withTimeout(SHORT_TIMEOUT))));
        }
    }

    /**
     * EXT-008: an ambiguous source raises AmbiguousLocatorException, never reinterpreted as absent.
     */
    @Test
    void anAmbiguousSourceRaisesAmbiguousLocatorException() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/ambiguous-source")) {
            assertThatExceptionOfType(AmbiguousLocatorException.class)
                    .isThrownBy(
                            () ->
                                    page.extract(
                                            ExtractionRequest.text(
                                                    LocatorDefinition.forRole(ElementRole.BUTTON)
                                                            .named("Confirm")
                                                            .withTimeout(SHORT_TIMEOUT))));
        }
    }

    /** EXT-009: extraction inside a single iframe. */
    @Test
    void extractsFromInsideAnIframe() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/iframe-simple")) {
            IFrame checkout = page.frame().named("checkout").single();

            ExtractionResult<String> result =
                    checkout.extract(
                            ExtractionRequest.text(LocatorDefinition.element().withId("amount")));

            assertThat(result.value()).isEqualTo("250 USD");
        }
    }

    /** EXT-010: extraction inside a nested iframe. */
    @Test
    void extractsFromInsideANestedIframe() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/iframe-nested")) {
            IFrame outer = page.frame().named("outer").single();
            IFrame inner = outer.frame().named("inner").single();

            ExtractionResult<String> result =
                    inner.extract(
                            ExtractionRequest.text(LocatorDefinition.element().withId("amount")));

            assertThat(result.value()).isEqualTo("250 USD");
        }
    }

    /**
     * EXT-011: a frame replaced by another matching the same semantic identity is still followed -
     * extraction reads from the current document, never a stale reference to the replaced one.
     */
    @Test
    void extractsFromTheReplacementFrameAfterFrameReplacement() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/iframe-replacement")) {
            IFrame checkout = page.frame().named("checkout").single();
            page.evaluate("replaceCheckoutFrame()");

            ExtractionResult<String> result =
                    checkout.extract(
                            ExtractionRequest.text(LocatorDefinition.element().withId("amount")));

            assertThat(result.value()).isEqualTo("275 USD");
        }
    }

    /** Conversion into a typed value works end-to-end through a real browser read. */
    @Test
    void convertsAnExtractedValueEndToEnd() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/dynamic-value")) {
            ExtractionResult<Integer> result =
                    page.extract(
                            ExtractionRequest.value(LocatorDefinition.element().withId("quantity"))
                                    .convert(IValueConverter.toInteger()));

            assertThat(result.value()).isEqualTo(1);
        }
    }

    /**
     * EXT-012: {@code extractTable} resolves through {@code locateSingle}, exactly like scalar
     * {@code extract} - two equally valid tables raise {@code AmbiguousLocatorException}, never
     * silently resolving to whichever one ranks first.
     */
    @Test
    void anAmbiguousTableSourceRaisesAmbiguousLocatorException() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/ambiguous-table")) {
            assertThatExceptionOfType(AmbiguousLocatorException.class)
                    .isThrownBy(
                            () ->
                                    page.extractTable(
                                            LocatorDefinition.css("table")
                                                    .withTimeout(SHORT_TIMEOUT)));
        }
    }

    /**
     * EXT-013: a table nested inside one of an outer table's cells never contributes its own
     * headers or rows to the outer table's extraction - every selector extractTable uses is
     * anchored to the outer table's own direct children, never an unbounded descendant search.
     */
    @Test
    void extractingATableNeverAbsorbsANestedTablesHeadersOrRows() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/nested-table")) {
            ExtractionResult<ExtractedTable> result =
                    page.extractTable(LocatorDefinition.element().withId("outer"));

            ExtractedTable table = result.value();
            assertThat(table.headers()).containsExactly("Name", "Price");
            assertThat(table.rows()).hasSize(2);
            assertThat(table.cell(0, "Name")).contains("Laptop B");
            assertThat(table.cell(1, "Name")).contains("Mouse");
        }
    }

    /**
     * EXT-014: list extraction's provenance carries the scope path actually resolved inside a frame
     * - a frame document is its own independent scope chain root (see {@code LocatorScope#frame}),
     * so this is {@code Frame[name="catalog"]}, never the page's own {@code Page} baseline.
     */
    @Test
    void extractListInsideAFrameReportsTheFramesScopePath() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/iframe-list")) {
            IFrame catalog = page.frame().named("catalog").single();

            ExtractionResult<List<String>> result =
                    catalog.extractList(ExtractionRequest.text(LocatorDefinition.css("ul li")));

            assertThat(result.value()).containsExactly("Laptop B", "Mouse");
            assertThat(result.provenance().scopePath()).containsExactly("Frame[name=\"catalog\"]");
        }
    }

    /**
     * EXT-015: list extraction's provenance inside a nested frame reports that innermost frame's
     * own scope ({@code Frame[name="inner-catalog"]}) - never the outer frame's identity and never
     * the page's own baseline, since each frame document is resolved as its own independent scope
     * chain root rather than a descendant appended to its parent's path.
     */
    @Test
    void extractListInsideANestedFrameReportsTheInnermostFramesScopePath() throws Exception {
        try (var support = ExtractionTestSupport.start();
                var page = support.open("/extract/iframe-nested-list")) {
            IFrame outer = page.frame().named("outer-catalog").single();
            IFrame inner = outer.frame().named("inner-catalog").single();

            ExtractionResult<List<String>> result =
                    inner.extractList(ExtractionRequest.text(LocatorDefinition.css("ul li")));

            assertThat(result.value()).containsExactly("Laptop B", "Mouse");
            assertThat(result.provenance().scopePath())
                    .containsExactly("Frame[name=\"inner-catalog\"]");
        }
    }
}
