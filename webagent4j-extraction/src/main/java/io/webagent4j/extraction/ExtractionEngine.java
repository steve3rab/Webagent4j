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
import io.webagent4j.locator.LocatorAllResult;
import io.webagent4j.locator.LocatorCandidate;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.api.LocatorDefinition;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>A scalar read ({@link #extract}/{@link #extractTable}) resolves through {@link
 * ILocatorEngine#locateSingle}, never {@link ILocatorEngine#locate}: {@code locate} silently
 * returns the highest-ranked candidate even when several candidates are equally valid, which is
 * exactly the ambiguity a scalar extraction must never absorb. Zero candidates still raises a typed
 * not-found failure, and two or more equally valid candidates always raise {@code
 * AmbiguousLocatorException} - never a silently best-ranked guess.
 *
 * <p>A genuine failure resolving the source (a typed not-found, an ambiguous match, or an opaque
 * backend/runtime failure) always propagates unchanged from {@link ILocatorEngine}; this class
 * never catches or reinterprets it. The only failures this class itself raises are
 * extraction-specific: {@link ExtractionAttributeMissingException} when the resolved element exists
 * but lacks the requested attribute, and whatever {@link ExtractionRequest#convertAndValidate}
 * itself raises.
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
     * Resolves {@code request}'s source to one unambiguous element ({@link
     * ILocatorEngine#locateSingle}) and reads, converts, and validates its data.
     */
    public <T> ExtractionResult<T> extract(
            ILiveLocatorContext context, ExtractionRequest<T> request) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        LocatorResult result = engine.locateSingle(context, request.source());
        String raw = readRaw(result.element(), request);
        T value = request.convertAndValidate(raw);
        return new ExtractionResult<>(
                value, Optional.of(raw), provenance(result.diagnostics().scopePath(), request));
    }

    /**
     * Resolves every element {@code request}'s source matches ({@link ILocatorEngine#locateAll}),
     * reads, converts, and validates each one's data, and returns the results in DOM order ({@link
     * LocatorCandidate#domOrder()}) - the deterministic order a caller asking for a whole
     * collection expects, which is not necessarily the engine's internal rank order. DOM order is
     * used here only because a collection was explicitly requested; it is never used as an implicit
     * tie-break for {@link #extract}, which stays on {@code locateSingle}'s ambiguity semantics. A
     * conversion, validation, or missing-attribute failure on any one candidate fails the whole
     * request rather than silently dropping that entry - list extraction never returns a partial,
     * silently-shortened result.
     */
    public <T> ExtractionResult<List<T>> extractList(
            ILiveLocatorContext context, ExtractionRequest<T> request) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        LocatorAllResult result = engine.locateAllWithScopePath(context, request.source());
        List<LocatorCandidate> orderedByDom =
                result.candidates().stream()
                        .sorted(Comparator.comparingInt(LocatorCandidate::domOrder))
                        .toList();
        List<T> values = new ArrayList<>(orderedByDom.size());
        for (LocatorCandidate candidate : orderedByDom) {
            String raw = readRaw(candidate.element(), request);
            values.add(request.convertAndValidate(raw));
        }
        return new ExtractionResult<>(
                List.copyOf(values), Optional.empty(), provenance(result.scopePath(), request));
    }

    /**
     * Resolves {@code source} to one accessible HTML table ({@link ILocatorEngine#locateSingle})
     * and reads its structure: headers from the table's own direct {@code thead > tr > th}/{@code
     * thead > tr > td} (empty when the table has no {@code thead} - never guessed from an untagged
     * first row), and every direct {@code tbody}/{@code tfoot}/table-level {@code tr} not inside a
     * {@code thead}, each row's cells read from its own direct {@code th}/{@code td} children in
     * DOM order. Every selector is anchored to direct children of the table (or of one of its own
     * rows) so a table nested inside one of this table's cells never contributes its own headers,
     * rows, or cells to this table's result. Every read reuses the same locator engine ({@code
     * element.find().css(...)}), never a Playwright-specific primitive.
     */
    public ExtractionResult<ExtractedTable> extractTable(
            ILiveLocatorContext context, LocatorDefinition source) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(source, "source");
        LocatorResult result = engine.locateSingle(context, source);
        IElement table = result.element();
        List<String> headers = readCells(table, "> thead > tr > th, > thead > tr > td");
        List<ExtractedRow> rows =
                table.find().css("> tbody > tr, > tr, > tfoot > tr").all().stream()
                        .map(row -> new ExtractedRow(readCells(row, "> th, > td")))
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

    private static ExtractionProvenance provenance(
            List<String> scopePath, ExtractionRequest<?> request) {
        return new ExtractionProvenance(
                scopePath, request.source(), request.readType(), request.attributeName());
    }

    private static List<String> readCells(IElement scope, String selector) {
        return scope.find().css(selector).all().stream().map(IElement::text).toList();
    }
}
