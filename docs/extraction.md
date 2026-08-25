# Extraction

Extraction reuses the same live locator engine as normal find/locate operations. It does not maintain a second DOM-search implementation.

```text
resolve source -> read raw value -> convert -> validate -> ExtractionResult
```

## Operations

- `extract(request)` resolves exactly one source; zero is not-found and ambiguity fails.
- `extractList(request)` resolves all accepted candidates and returns values in DOM order. Empty match is an empty list.
- `extractTable(definition)` resolves one HTML table and extracts its direct table structure.
- `IElement.extract(request)` reads directly from an already-resolved element and does not perform another locator search.

Unexpected backend/runtime failure is not converted to an empty extraction.

## Raw reads

`ExtractionRequest` supports text, one named attribute, or current form-control value. Form value reads live `element.value`, not only the static HTML attribute.

A missing attribute on a present element is `ExtractionAttributeMissingException`, distinct from locator not-found.

## Conversion

Converters deterministically transform the raw string. Built-ins include identity, integer/long/decimal/boolean, and date conversions. Invalid conversion raises `ExtractionConversionException`; converter-returned null is not a successful extraction.

Changing a request's result type with `convert(...)` discards any validator bound to the old type. Attach a compatible validator after conversion.

## Validation

Validators run after conversion. Built-ins cover non-blank, ranges, regex patterns, and explicit predicates/descriptions. Validation failure is separate from conversion failure.

Custom converter/validator callbacks are trusted application code; they should be deterministic and must not leak secrets through arbitrary messages.

## Lists

List extraction returns complete success or fails when one entry cannot be read/converted/validated. It does not silently drop a bad item. DOM order is used because the caller explicitly requested a collection; it is not used to hide scalar ambiguity.

## Tables

HTML table extraction uses direct table children so nested tables do not contribute their rows/headers to the outer result. A table without explicit `thead` does not invent a header row. Irregular row widths are preserved rather than padded.

Visual tables built from arbitrary `div` layout are not reconstructed.

## Provenance

Results retain source locator intent, read kind/attribute where applicable, and scope path. Scalar results can retain the pre-conversion raw value. Provenance intentionally does not invent a page URL snapshot that might race navigation independently of the locator read.

Element-scoped extraction has no locator scope traversal of its own, so its provenance scope path reflects that direct-read boundary.

## Frames

Frame extraction resolves the frame's live pending scope during the operation. Frame disappearance/ambiguity/backend failure follows the same fail-closed classification as locator operations.

## Composition boundary

Extraction itself does not orchestrate pagination, crawling, multi-page workflows, infinite scroll, or distributed scraping. Those capabilities must be composed explicitly with crawler/workflow/application logic rather than being implied by extraction.
