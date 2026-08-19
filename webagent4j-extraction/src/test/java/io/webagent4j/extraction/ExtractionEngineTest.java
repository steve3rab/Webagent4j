package io.webagent4j.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.webagent4j.dom.IElement;
import io.webagent4j.extraction.api.ExtractedTable;
import io.webagent4j.extraction.api.ExtractionAttributeMissingException;
import io.webagent4j.extraction.api.ExtractionConversionException;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.extraction.api.ExtractionValidationException;
import io.webagent4j.extraction.api.IExtractionValidator;
import io.webagent4j.extraction.api.IValueConverter;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.ILiveLocatorContext;
import io.webagent4j.locator.ILocatorBackend;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorContext;
import io.webagent4j.locator.LocatorDiagnostics;
import io.webagent4j.locator.LocatorDiagnosticsLevel;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.LocatorResolutionPolicy;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IFind;
import io.webagent4j.locator.api.ILocator;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExtractionEngine} in isolation from any real {@link ILocatorEngine}: a fake
 * {@link ILocatorEngine} returns hand-built {@link LocatorResult}/{@link LocatorCandidate} values
 * so these tests exercise only extraction's own read/convert/validate pipeline and failure
 * taxonomy, never locator resolution or scoring itself (already covered by {@code
 * webagent4j-locator}'s own tests).
 */
class ExtractionEngineTest {

    private static final LocatorDefinition SOURCE =
            LocatorDefinition.forRole(ElementRole.HEADING).named("Total");

    @Test
    void extractsTextFromTheResolvedElement() {
        IElement element = element("Laptop B", Map.of(), "");
        ExtractionEngine extraction = new ExtractionEngine(fakeEngine(element));

        ExtractionResult<String> result =
                extraction.extract(context(), ExtractionRequest.text(SOURCE));

        assertThat(result.value()).isEqualTo("Laptop B");
        assertThat(result.rawValue()).contains("Laptop B");
        assertThat(result.provenance().readType())
                .isEqualTo(io.webagent4j.extraction.api.ExtractionReadType.TEXT);
    }

    @Test
    void extractsANamedAttribute() {
        IElement element = element("Laptop B", Map.of("href", "/products/laptop-b"), "");
        ExtractionEngine extraction = new ExtractionEngine(fakeEngine(element));

        ExtractionResult<String> result =
                extraction.extract(context(), ExtractionRequest.attribute(SOURCE, "href"));

        assertThat(result.value()).isEqualTo("/products/laptop-b");
        assertThat(result.provenance().attributeName()).contains("href");
    }

    @Test
    void aMissingAttributeIsDistinguishedFromAMissingElement() {
        IElement element = element("Laptop B", Map.of(), "");
        ExtractionEngine extraction = new ExtractionEngine(fakeEngine(element));

        assertThatExceptionOfType(ExtractionAttributeMissingException.class)
                .isThrownBy(
                        () ->
                                extraction.extract(
                                        context(), ExtractionRequest.attribute(SOURCE, "data-sku")))
                .satisfies(failure -> assertThat(failure.attributeName()).isEqualTo("data-sku"));
    }

    @Test
    void extractsTheLiveFormControlValue() {
        IElement element = element("", Map.of(), "42");
        ExtractionEngine extraction = new ExtractionEngine(fakeEngine(element));

        ExtractionResult<String> result =
                extraction.extract(context(), ExtractionRequest.value(SOURCE));

        assertThat(result.value()).isEqualTo("42");
    }

    @Test
    void convertsAndValidatesInPipelineOrder() {
        IElement element = element("42", Map.of(), "");
        ExtractionEngine extraction = new ExtractionEngine(fakeEngine(element));
        ExtractionRequest<Integer> request =
                ExtractionRequest.text(SOURCE)
                        .convert(IValueConverter.toInteger())
                        .validate(IExtractionValidator.range(0, 100));

        ExtractionResult<Integer> result = extraction.extract(context(), request);

        assertThat(result.value()).isEqualTo(42);
        assertThat(result.rawValue()).contains("42");
    }

    @Test
    void aConversionFailureIsDistinguishedFromAValidationFailure() {
        IElement element = element("not a number", Map.of(), "");
        ExtractionEngine extraction = new ExtractionEngine(fakeEngine(element));
        ExtractionRequest<Integer> request =
                ExtractionRequest.text(SOURCE).convert(IValueConverter.toInteger());

        assertThatExceptionOfType(ExtractionConversionException.class)
                .isThrownBy(() -> extraction.extract(context(), request));
    }

    @Test
    void aValidationFailureOnlyRunsAfterAWorkingConversion() {
        IElement element = element("999", Map.of(), "");
        ExtractionEngine extraction = new ExtractionEngine(fakeEngine(element));
        ExtractionRequest<Integer> request =
                ExtractionRequest.text(SOURCE)
                        .convert(IValueConverter.toInteger())
                        .validate(IExtractionValidator.range(0, 100));

        assertThatExceptionOfType(ExtractionValidationException.class)
                .isThrownBy(() -> extraction.extract(context(), request));
    }

    @Test
    void aNotFoundSourcePropagatesUnchangedRatherThanBeingReinterpreted() {
        ILocatorEngine failing =
                new FailingLocatorEngine(new LocatorNotFoundException("no element matched"));
        ExtractionEngine extraction = new ExtractionEngine(failing);

        assertThatExceptionOfType(LocatorNotFoundException.class)
                .isThrownBy(() -> extraction.extract(context(), ExtractionRequest.text(SOURCE)));
    }

    @Test
    void anAmbiguousSourceIsNeverReinterpretedAsNotFound() {
        ILocatorEngine failing =
                new FailingLocatorEngine(new AmbiguousLocatorException("two candidates matched"));
        ExtractionEngine extraction = new ExtractionEngine(failing);

        assertThatExceptionOfType(AmbiguousLocatorException.class)
                .isThrownBy(() -> extraction.extract(context(), ExtractionRequest.text(SOURCE)));
    }

    @Test
    void anOpaqueBackendFailurePropagatesUnchangedRatherThanBeingAbsorbed() {
        RuntimeException backendFailure = new IllegalStateException("browser disconnected");
        ILocatorEngine failing = new FailingLocatorEngine(backendFailure);
        ExtractionEngine extraction = new ExtractionEngine(failing);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> extraction.extract(context(), ExtractionRequest.text(SOURCE)))
                .isSameAs(backendFailure);
    }

    @Test
    void extractListPreservesTheEnginesDeterministicOrder() {
        IElement first = element("Laptop B", Map.of(), "");
        IElement second = element("Mouse", Map.of(), "");
        ILocatorEngine engine = fakeListEngine(List.of(first, second));
        ExtractionEngine extraction = new ExtractionEngine(engine);

        ExtractionResult<List<String>> result =
                extraction.extractList(context(), ExtractionRequest.text(SOURCE));

        assertThat(result.value()).containsExactly("Laptop B", "Mouse");
        assertThat(result.rawValue()).isEmpty();
    }

    @Test
    void extractListOnNoMatchesReturnsAnEmptyListNotAFailure() {
        ILocatorEngine engine = fakeListEngine(List.of());
        ExtractionEngine extraction = new ExtractionEngine(engine);

        ExtractionResult<List<String>> result =
                extraction.extractList(context(), ExtractionRequest.text(SOURCE));

        assertThat(result.value()).isEmpty();
    }

    @Test
    void extractListFailsTheWholeRequestWhenOneCandidateFailsConversion() {
        IElement good = element("42", Map.of(), "");
        IElement bad = element("not a number", Map.of(), "");
        ILocatorEngine engine = fakeListEngine(List.of(good, bad));
        ExtractionEngine extraction = new ExtractionEngine(engine);
        ExtractionRequest<Integer> request =
                ExtractionRequest.text(SOURCE).convert(IValueConverter.toInteger());

        assertThatExceptionOfType(ExtractionConversionException.class)
                .isThrownBy(() -> extraction.extractList(context(), request));
    }

    @Test
    void extractTableReadsHeadersAndRowsInDomOrder() {
        IElement headerA = element("Name", Map.of(), "");
        IElement headerB = element("Price", Map.of(), "");
        IElement rowOneA = element("Laptop B", Map.of(), "");
        IElement rowOneB = element("999", Map.of(), "");
        IElement rowTwoA = element("Mouse", Map.of(), "");
        IElement rowTwoB = element("19", Map.of(), "");
        IElement rowOne = tableElementWithCells(List.of(rowOneA, rowOneB));
        IElement rowTwo = tableElementWithCells(List.of(rowTwoA, rowTwoB));
        IElement table = tableElement(List.of(headerA, headerB), List.of(rowOne, rowTwo));
        ExtractionEngine extraction = new ExtractionEngine(fakeEngine(table));

        ExtractionResult<ExtractedTable> result = extraction.extractTable(context(), SOURCE);

        ExtractedTable extracted = result.value();
        assertThat(extracted.headers()).containsExactly("Name", "Price");
        assertThat(extracted.cell(0, 0)).isEqualTo("Laptop B");
        assertThat(extracted.cell(0, "Price")).contains("999");
        assertThat(extracted.cell(1, 0)).isEqualTo("Mouse");
    }

    @Test
    void extractTableOnATableWithNoTheadReturnsEmptyHeaders() {
        IElement rowOneA = element("Laptop B", Map.of(), "");
        IElement rowOne = tableElementWithCells(List.of(rowOneA));
        IElement table = tableElement(List.of(), List.of(rowOne));
        ExtractionEngine extraction = new ExtractionEngine(fakeEngine(table));

        ExtractionResult<ExtractedTable> result = extraction.extractTable(context(), SOURCE);

        assertThat(result.value().headers()).isEmpty();
        assertThat(result.value().rows()).hasSize(1);
    }

    // -- fixtures -----------------------------------------------------------------------------

    private static IElement element(String text, Map<String, String> attributes, String value) {
        IElement element = mock(IElement.class);
        when(element.text()).thenReturn(text);
        when(element.attributes()).thenReturn(attributes);
        when(element.value()).thenReturn(value);
        return element;
    }

    /** A table row element exposing {@code cells} under the {@code "th, td"} selector. */
    private static IElement tableElementWithCells(List<IElement> cells) {
        IElement row = mock(IElement.class);
        stubCss(row, Map.of("th, td", cells));
        return row;
    }

    /** A table element exposing headers and rows under extractTable's exact selectors. */
    private static IElement tableElement(List<IElement> headerCells, List<IElement> rows) {
        IElement table = mock(IElement.class);
        stubCss(
                table,
                Map.of(
                        "thead th, thead td", headerCells,
                        "tr:not(thead tr)", rows));
        return table;
    }

    @SuppressWarnings("unchecked")
    private static void stubCss(IElement element, Map<String, List<IElement>> selectorResults) {
        IFind<IElement> find = mock(IFind.class);
        when(element.find()).thenReturn(find);
        selectorResults.forEach(
                (selector, matches) -> {
                    ILocator<IElement> locator = mock(ILocator.class);
                    when(locator.all()).thenReturn(matches);
                    when(find.css(selector)).thenReturn(locator);
                });
    }

    private static ILiveLocatorContext context() {
        LocatorContext baseline =
                new LocatorContext(
                        mock(ILocatorBackend.class), LocatorScope.page(), LocatorConfig.defaults());
        return ILiveLocatorContext.fixed(baseline);
    }

    private static ILocatorEngine fakeEngine(IElement element) {
        return new ILocatorEngine() {
            @Override
            public LocatorResult locate(ILiveLocatorContext context, LocatorDefinition definition) {
                return result(definition, element);
            }

            @Override
            public LocatorResult locateSingle(
                    ILiveLocatorContext context, LocatorDefinition definition) {
                return result(definition, element);
            }

            @Override
            public List<LocatorCandidate> locateAll(
                    ILiveLocatorContext context, LocatorDefinition definition) {
                return List.of(candidate(element));
            }
        };
    }

    private static ILocatorEngine fakeListEngine(List<IElement> elements) {
        return new ILocatorEngine() {
            @Override
            public LocatorResult locate(ILiveLocatorContext context, LocatorDefinition definition) {
                throw new UnsupportedOperationException("not used by list extraction");
            }

            @Override
            public LocatorResult locateSingle(
                    ILiveLocatorContext context, LocatorDefinition definition) {
                throw new UnsupportedOperationException("not used by list extraction");
            }

            @Override
            public List<LocatorCandidate> locateAll(
                    ILiveLocatorContext context, LocatorDefinition definition) {
                return elements.stream().map(ExtractionEngineTest::candidate).toList();
            }
        };
    }

    private static final class FailingLocatorEngine implements ILocatorEngine {
        private final RuntimeException failure;

        FailingLocatorEngine(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public LocatorResult locate(ILiveLocatorContext context, LocatorDefinition definition) {
            throw failure;
        }

        @Override
        public LocatorResult locateSingle(
                ILiveLocatorContext context, LocatorDefinition definition) {
            throw failure;
        }

        @Override
        public List<LocatorCandidate> locateAll(
                ILiveLocatorContext context, LocatorDefinition definition) {
            throw failure;
        }
    }

    private static LocatorResult result(LocatorDefinition definition, IElement element) {
        LocatorCandidate candidate = candidate(element);
        return new LocatorResult(
                definition,
                element,
                LocatorStrategyType.ACCESSIBLE_NAME,
                1.0,
                1.0,
                true,
                List.of(candidate),
                diagnostics(definition));
    }

    private static LocatorCandidate candidate(IElement element) {
        return new LocatorCandidate(
                "identity-" + System.identityHashCode(element),
                element,
                LocatorStrategyType.ACCESSIBLE_NAME,
                1.0,
                1.0,
                0,
                List.of(),
                true,
                true,
                true);
    }

    private static LocatorDiagnostics diagnostics(LocatorDefinition definition) {
        return new LocatorDiagnostics(
                definition,
                LocatorResolutionPolicy.BALANCED,
                LocatorDiagnosticsLevel.BASIC,
                List.of("Page"),
                List.of(),
                List.of(),
                1,
                0,
                0,
                List.of(),
                1,
                0,
                Optional.empty(),
                Duration.ZERO,
                false,
                Set.of(),
                Optional.empty(),
                List.of());
    }
}
