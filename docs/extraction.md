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

`IPage`, `IFrame`, and their Playwright implementations all expose the same three operations, added
as `default` methods that report "extraction is not supported by this backend" unless a backend
overrides them (the Playwright adapter does) - so adding them never breaks source compatibility
with an existing `IPage`/`IFrame` implementation:

- `extract(ExtractionRequest<T>)` - resolves the source to exactly one unambiguous candidate
  (`ILocatorEngine#locateSingle`, the same contract `single()` already has) and reads its data. Zero
  candidates raises `LocatorNotFoundException`; two or more equally valid candidates always raise
  `AmbiguousLocatorException` - a scalar extraction never silently falls back to whichever candidate
  the engine happens to rank first.
- `extractList(ExtractionRequest<T>)` - resolves every matching candidate (`ILocatorEngine#locateAll`)
  and reads each one's data, returned in DOM order.
- `extractTable(LocatorDefinition)` - resolves one accessible HTML table the same way `extract` does
  (`locateSingle`) and reads its structure; two equally valid tables raise `AmbiguousLocatorException`
  rather than silently picking one.

## Text, attributes, and form values

`ExtractionRequest` has three raw read types, each with a static factory:

```java
ExtractionRequest.text(source)                 // IElement#text()'s own value, unchanged
ExtractionRequest.attribute(source, "href")    // one named HTML attribute
ExtractionRequest.value(source)                // the current live form-control value
```

TEXT extraction is exactly whatever `IElement#text()` already returns; extraction applies no
second, independent text normalizer of its own. If a backend's `text()` collapses whitespace a
particular way (or doesn't, for characters such as a non-breaking space), that is the extraction
contract too - not a stronger, extraction-specific guarantee.

`value()` reads the control's actual current value (`element.value` in the DOM), not the static
HTML `value` attribute - a value changed by the user or by JavaScript after page load is read
correctly.

An attribute that is not present on an otherwise-present element raises
`ExtractionAttributeMissingException`, distinct from the element itself being absent (which is
still reported by the locator layer's own `LocatorNotFoundException`).

## Lists

`extractList` reads every candidate `request.source()` matches, returned in DOM order
(`LocatorCandidate#domOrder()`) - explicitly sorted that way, since `ILocatorEngine#locateAll`'s own
contract is deterministic *rank* order, not necessarily DOM order:

```java
ExtractionResult<List<String>> names = page.extractList(
        ExtractionRequest.text(LocatorDefinition.css("[data-testid='product-name']")));
```

DOM order is used here only because a whole collection was explicitly requested; it is never used
as an implicit tie-break for `extract`'s own ambiguity decision, which stays entirely on
`locateSingle`'s explicit AMBIGUOUS-vs-single semantics. An empty match is an empty list, never an
error. A conversion, validation, or missing-attribute failure on any one candidate fails the whole
request - list extraction never silently returns a shorter list with the bad entry dropped.

## Tables

`extractTable` resolves one accessible HTML table (`locateSingle`) and reads its structure directly
with `thead`/`tbody`/`tr`/`th`/`td`, reusing the same locator engine (`element.find().css(...)`)
rather than a Playwright-specific primitive:

```java
ExtractedTable table = page.extractTable(LocatorDefinition.css("table")).value();
table.headers();           // ["Name", "Price"]
table.cell(0, "Price");    // Optional<String>, resolved by header name
table.cell(0, 1);          // "999", resolved by DOM column index
```

Headers come from the table's own direct `thead > tr > th`/`thead > tr > td` only - a table with no
`thead` reports an empty header list rather than guessing that an untagged first row is the header.
Rows are read from the table's own direct `tbody`/`tfoot`/table-level `tr` children (never a row
inside its `thead`), each row's cells from its own direct `th`/`td` children, in DOM order, exactly
as found: a row with fewer cells than the header count is reported at its real width, never padded
with empty cells to match. Every selector is anchored to *direct* children at each level, so a table
nested inside one of this table's own cells never contributes its own headers, rows, or cells to
this table's result.

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
string and target type for diagnosis. A converter is mandatory on every `ExtractionRequest` -
`text()`/`attribute()`/`value()` start with `identity()` - so there is never an untyped request an
engine would have to guess a fallback for; a converter that returns `null` is itself treated as a
conversion failure, since a successful `ExtractionResult` can never carry a `null` value.

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
source was resolved in (the same hierarchical shape `LocatorDiagnostics` already uses). `extract`
and `extractTable` report the scope path from the same `LocatorResult` the read itself came from;
`extractList` reports the scope path from the same `locateAll` resolution attempt that produced its
candidates (`ILocatorEngine#locateAllWithScopePath`) rather than the caller's starting baseline
scope, which matters inside a frame - a frame document is resolved as its own independent scope
chain root (for example `Frame[name="checkout"]`), not appended onto its parent's path, so
provenance for a list read inside a frame correctly reports that frame's own scope rather than the
page's.

A scalar `text()`/`attribute()`/`value()` result also carries its pre-conversion raw string on
`ExtractionResult#rawValue()`; a list or table result does not, since it aggregates many raw reads
rather than carrying one.

Provenance deliberately does not include the current page URL. `webagent4j-locator`'s backend-
neutral contracts (`ILocatorBackend`, `LocatorContext`, `LocatorScope`) have no notion of "current
URL" at all, and capturing one separately at `IPage`/`IFrame` level, before resolution begins, risks
representing a different document than the one a value was actually read from if a navigation
happens mid-wait - exactly the kind of stale-snapshot bug this codebase avoids elsewhere. Adding it
properly would need new plumbing through the locator layer disproportionate to this phase; the scope
path's frame boundaries already make cross-document provenance inspectable without it.

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

`IElement#extract(ExtractionRequest<T>)` reads, converts, and validates directly from an
already-resolved element - no locator search at all, since the element is already the one the
caller was told was selected:

```java
LocatorDefinition quantityDefinition = LocatorDefinition.element().withId("quantity");
IElement quantityField = page.resolve(quantityDefinition);
ExtractionResult<Integer> quantity = quantityField.extract(
        ExtractionRequest.value(quantityDefinition).convert(IValueConverter.toInteger()));
```

Its `ExtractionProvenance#scopePath()` is always empty, since no locator scope is resolved to reach
an already-resolved element - the request's `source()` is retained only as descriptive metadata, not
searched for. `IElement.extract()` lives in `webagent4j-dom`, which depends on
`webagent4j-extraction-api` for exactly this one method (see [Modules](#modules)); it duplicates no
logic from `webagent4j-extraction`'s `ExtractionEngine` - both share the same
`ExtractionRequest#convertAndValidate(String)` pipeline step.

## Modules

`webagent4j-extraction-api` is backend-neutral and depends only on `webagent4j-locator-api`: it
defines `ExtractionRequest`, `ExtractionResult`, `ExtractionProvenance`, the converter/validator
contracts, and the failure taxonomy. `webagent4j-extraction` is the deterministic engine
(`ExtractionEngine`), reusing `webagent4j-locator`'s `ILocatorEngine`/`ILiveLocatorContext` rather
than a second resolution engine. Neither module depends on Playwright.

`webagent4j-dom` also depends on `webagent4j-extraction-api`, for `IElement#extract` (see
[Element-scoped reads](#element-scoped-reads)) - the one-directional edge `dom -> extraction.api`,
never the reverse, is itself an ArchUnit-enforced rule
(`extractionApiRemainsIndependentFromDom`).

## Limitations

This phase does not implement, and explicitly defers to later phases:

- Crawling, pagination, or multi-page workflows
- Distributed or scraping-at-scale scenarios
- AI-based schema inference, OCR, or visual/computer-vision extraction
- Generalized automatic `JSON-LD`/structured-data discovery
- Infinite-scroll orchestration
- Advanced network-level retries
- Reconstructing a "visual table" laid out with non-table markup
