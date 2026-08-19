# Extraction

WebAgent4J extracts data from a page, an element, or a frame with the same deterministic locator
resolution `find()`/`locate()` already use - never a second, parallel DOM search. An extraction is
always the same pipeline:

```text
resolve source (LocatorEngine, live re-resolution, WaitEngine) -> read raw value
                                                                  -> convert -> validate -> ExtractionResult<T>
```

```java
ExtractionResult<String> total = page.extract(
        ExtractionRequest.text(LocatorDefinition.forRole(ElementRole.HEADING).named("Total")));

System.out.println(total.value());
```

`IPage`, `IFrame`, and their Playwright implementations all expose the same three operations:

- `extract(ExtractionRequest<T>)` - resolves one unambiguous source and reads its data.
- `extractList(ExtractionRequest<T>)` - resolves every matching source, in the engine's
  deterministic score and DOM order, and reads each one's data.
- `extractTable(LocatorDefinition)` - resolves one accessible HTML table and reads its structure.

## Text, attributes, and form values

`ExtractionRequest` has three raw read types, each with a static factory:

```java
ExtractionRequest.text(source)                 // normalized visible text
ExtractionRequest.attribute(source, "href")    // one named HTML attribute
ExtractionRequest.value(source)                // the current live form-control value
```

`value()` reads the control's actual current value (`element.value` in the DOM), not the static
HTML `value` attribute - a value changed by the user or by JavaScript after page load is read
correctly.

An attribute that is not present on an otherwise-present element raises
`ExtractionAttributeMissingException`, distinct from the element itself being absent (which is
still reported by the locator layer's own `LocatorNotFoundException`).

## Lists

`extractList` reads every candidate `request.source()` matches, in the same deterministic score and
DOM order `find()...all()` already guarantees:

```java
ExtractionResult<List<String>> names = page.extractList(
        ExtractionRequest.text(LocatorDefinition.css("[data-testid='product-name']")));
```

An empty match is an empty list, never an error. A conversion, validation, or missing-attribute
failure on any one candidate fails the whole request - list extraction never silently returns a
shorter list with the bad entry dropped.

## Tables

`extractTable` resolves one accessible HTML table and reads its structure directly with `thead`/
`tbody`/`tr`/`th`/`td`, reusing the same locator engine (`element.find().css(...)`) rather than a
Playwright-specific primitive:

```java
ExtractedTable table = page.extractTable(LocatorDefinition.css("table")).value();
table.headers();           // ["Name", "Price"]
table.cell(0, "Price");    // Optional<String>, resolved by header name
table.cell(0, 1);          // "999", resolved by DOM column index
```

Headers come from `thead th`/`thead td` only - a table with no `thead` reports an empty header
list rather than guessing that an untagged first row is the header. Rows are read exactly as
found: a row with fewer cells than the header count is reported at its real width, never padded
with empty cells to match.

This first iteration targets real HTML tables. Reconstructing a "visual table" built from `div`s
is out of scope.

## Conversion

`IValueConverter<T>` deterministically converts a raw string to a typed value - never a
probabilistic or "best effort" guess:

```java
ExtractionRequest.text(source).convert(IValueConverter.toInteger())
```

Built-in converters: `identity()`, `toInteger()`, `toLong()`, `toBigDecimal()`, `toBoolean()`
(accepts exactly `"true"`/`"false"`, case-insensitively - never a heuristic like `"yes"`),
`toLocalDate()` (ISO-8601) and `toLocalDate(DateTimeFormatter)` for an explicit custom format. A
value that does not cleanly convert raises `ExtractionConversionException`, retaining the raw
string and target type for diagnosis.

`convert(...)` replaces a request's result type, so it always discards any previously attached
validator - a validator typed for the old result type can never apply to the new one. Attach
`validate(...)` again afterward.

## Validation

`IExtractionValidator<T>` runs after conversion has already succeeded:

```java
ExtractionRequest.text(source)
        .convert(IValueConverter.toInteger())
        .validate(IExtractionValidator.range(0, 100))
```

Built-in validators: `nonBlank()`, `range(min, max)`, `matches(Pattern)`, and
`predicate(Predicate<T>, description)`. A failed rule raises `ExtractionValidationException`,
distinct from a conversion failure.

## Provenance

Every `ExtractionResult` carries an `ExtractionProvenance`: the `LocatorDefinition` that resolved
the source, which raw datum was read, the attribute name when applicable, and the scope path the
source was resolved in (the same hierarchical shape `LocatorDiagnostics` already uses, including
any frame boundaries crossed to reach it). A scalar `text()`/`attribute()`/`value()` result also
carries its pre-conversion raw string on `ExtractionResult#rawValue()`; a list or table result does
not, since it aggregates many raw reads rather than carrying one.

## Failures

Extraction never returns `null` for a failure. A not-found or ambiguous source is reported exactly
like any other locator failure - `LocatorNotFoundException` or `AmbiguousLocatorException` - never
reinterpreted. A genuine backend or runtime failure (a disconnected browser, a closed context)
always propagates unchanged, matching the fail-closed contract every other locator operation in
this codebase already has. Extraction adds exactly three failure types of its own, always thrown
rather than silently substituting a default:

| Failure                              | Meaning                                              |
|---------------------------------------|-------------------------------------------------------|
| `ExtractionAttributeMissingException` | The element exists, but the requested attribute does not |
| `ExtractionConversionException`       | The raw value could not be deterministically converted |
| `ExtractionValidationException`       | The converted value failed a validation rule          |

## Frames

`IFrame#extract`/`extractList`/`extractTable` re-resolve the frame's own pending-scope chain fresh
on every poll, exactly like `IFrame#locate` already does: a frame that disappears, is replaced by
another with the same semantic identity, or becomes ambiguous mid-wait is caught the same way, and
extraction never captures a stale `Frame` or document-root snapshot from before the wait began.

```java
IFrame checkout = page.frame().named("checkout").single();
ExtractionResult<BigDecimal> total = checkout.extract(
        ExtractionRequest.text(LocatorDefinition.forRole(ElementRole.HEADING).named("Total"))
                .convert(IValueConverter.toBigDecimal()));
```

## Element-scoped reads

An already-resolved `IElement` needs no locator search at all, so there is no `element.extract()`
indirection: call `IElement#text()`/`attributes()`/`value()` directly, then apply a converter or
validator the same way `ExtractionRequest` does internally:

```java
Integer quantity = IValueConverter.toInteger().convert(element.value());
IExtractionValidator.range(0, 100).validate(quantity);
```

## Modules

`webagent4j-extraction-api` is backend-neutral and depends only on `webagent4j-locator-api`: it
defines `ExtractionRequest`, `ExtractionResult`, `ExtractionProvenance`, the converter/validator
contracts, and the failure taxonomy. `webagent4j-extraction` is the deterministic engine
(`ExtractionEngine`), reusing `webagent4j-locator`'s `ILocatorEngine`/`ILiveLocatorContext` rather
than a second resolution engine. Neither module depends on Playwright.

## Limitations

This phase does not implement, and explicitly defers to later phases:

- Crawling, pagination, or multi-page workflows
- Distributed or scraping-at-scale scenarios
- AI-based schema inference, OCR, or visual/computer-vision extraction
- Generalized automatic `JSON-LD`/structured-data discovery
- Infinite-scroll orchestration
- Advanced network-level retries
- Reconstructing a "visual table" laid out with non-table markup
