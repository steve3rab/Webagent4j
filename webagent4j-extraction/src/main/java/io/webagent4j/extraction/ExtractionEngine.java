package io.webagent4j.extraction;

import io.webagent4j.dom.IElement;
import io.webagent4j.extraction.api.ExtractedRow;
import io.webagent4j.extraction.api.ExtractedTable;
import io.webagent4j.extraction.api.ExtractionAttributeMissingException;
import io.webagent4j.extraction.api.ExtractionProvenance;
import io.webagent4j.extraction.api.ExtractionReadType;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.locator.ILiveLocatorContext;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.api.LocatorDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic, backend-neutral extraction engine: resolves an {@link ExtractionRequest}'s {@code
 * source} through the existing {@link ILocatorEngine} - the same engine, live-context
 * re-resolution, {@code WaitEngine}-driven waiting, and NOT_FOUND/AMBIGUOUS/backend-failure
 * classification every other locator caller already gets - then reads, converts, and validates the
 * raw value. This is not a second DOM resolution engine; it is one more caller of the existing one.
 *
 * <p>A genuine failure resolving the source (a typed not-found, an ambiguous match, or an opaque
 * backend/runtime failure) always propagates unchanged from {@link ILocatorEngine#locate}/{@link
 * ILocatorEngine#locateAll}; this class never catches or reinterprets it. The only failures this
 * class itself raises are extraction-specific: {@link ExtractionAttributeMissingException} when the
 * resolved element exists but lacks the requested attribute, and whatever {@link
 * io.webagent4j.extraction.api.IValueConverter}/{@link
 * io.webagent4j.extraction.api.IExtractionValidator} themselves raise.
 *
 * <p>Each extraction reads its already-resolved element's data directly - {@code resolve source ->
 * read value} - never a second, independent search that could silently read a different element
 * than the one the caller was told was selected.
 */
public final class ExtractionEngine {

    private final ILocatorEngine engine;

    /** Creates an engine delegating locator resolution to {@code engine}. */
    public ExtractionEngine(ILocatorEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Resolves {@code request}'s source to one unambiguous element and reads, converts, and
     * validates its data.
     */
    public <T> ExtractionResult<T> extract(
            ILiveLocatorContext context, ExtractionRequest<T> request) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        LocatorResult result = engine.locate(context, request.source());
        String raw = readRaw(result.element(), request);
        T value = convertAndValidate(raw, request);
        return new ExtractionResult<>(
                value, Optional.of(raw), provenance(result.diagnostics().scopePath(), request));
    }

    /**
     * Resolves every element {@code request}'s source matches, in the engine's deterministic score
     * and DOM order, and reads, converts, and validates each one's data. A conversion, validation,
     * or missing-attribute failure on any one candidate fails the whole request rather than
     * silently dropping that entry - list extraction never returns a partial, silently-shortened
     * result.
     */
    public <T> ExtractionResult<List<T>> extractList(
            ILiveLocatorContext context, ExtractionRequest<T> request) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        List<LocatorCandidate> candidates = engine.locateAll(context, request.source());
        List<T> values = new ArrayList<>(candidates.size());
        for (LocatorCandidate candidate : candidates) {
            String raw = readRaw(candidate.element(), request);
            values.add(convertAndValidate(raw, request));
        }
        return new ExtractionResult<>(
                List.copyOf(values),
                Optional.empty(),
                provenance(context.baseline().scope().path(), request));
    }

    /**
     * Resolves {@code source} to one accessible HTML table and reads its structure: headers from
     * {@code thead th}/{@code thead td} (empty when the table has no {@code thead} - never guessed
     * from an untagged first row), and every {@code tr} not inside a {@code thead}, each row's
     * cells read from its {@code th}/{@code td} children in DOM order. Every read reuses the same
     * locator engine ({@code element.find().css(...)}), never a Playwright-specific primitive.
     */
    public ExtractionResult<ExtractedTable> extractTable(
            ILiveLocatorContext context, LocatorDefinition source) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(source, "source");
        LocatorResult result = engine.locate(context, source);
        IElement table = result.element();
        List<String> headers = readCells(table, "thead th, thead td");
        List<ExtractedRow> rows =
                table.find().css("tr:not(thead tr)").all().stream()
                        .map(row -> new ExtractedRow(readCells(row, "th, td")))
                        .toList();
        ExtractedTable extracted = new ExtractedTable(headers, rows);
        ExtractionProvenance provenance =
                new ExtractionProvenance(
                        result.diagnostics().scopePath(),
                        source,
                        ExtractionReadType.TEXT,
                        Optional.empty());
        return new ExtractionResult<>(extracted, Optional.empty(), provenance);
    }

    private static String readRaw(IElement element, ExtractionRequest<?> request) {
        return switch (request.readType()) {
            case TEXT -> element.text();
            case VALUE -> element.value();
            case ATTRIBUTE -> {
                String name = request.attributeName().orElseThrow();
                String value = element.attributes().get(name);
                if (value == null) {
                    throw new ExtractionAttributeMissingException(name);
                }
                yield value;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T convertAndValidate(String raw, ExtractionRequest<T> request) {
        T value = request.converter().<T>map(converter -> converter.convert(raw)).orElse((T) raw);
        request.validator().ifPresent(validator -> validator.validate(value));
        return value;
    }

    private static ExtractionProvenance provenance(
            List<String> scopePath, ExtractionRequest<?> request) {
        return new ExtractionProvenance(
                scopePath, request.source(), request.readType(), request.attributeName());
    }

    private static List<String> readCells(IElement scope, String selector) {
        return scope.find().css(selector).all().stream().map(IElement::text).toList();
    }
}
