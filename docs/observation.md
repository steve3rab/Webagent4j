# Semantic observation

The observation engine answers three questions without exposing the raw DOM: what the page
contains, how its important regions relate, and which reliable actions are available. Calling
`page.observe()` performs one passive, bounded browser capture and returns a detached immutable
`Observation`. Reading that object never queries the live page again.

```java
Observation observation = page.observe();

for (SemanticElement button : observation.buttons()) {
    System.out.println(button.accessibleName());
}
```

## Semantic model

`PageMetadata` records the URL, title, language, charset, ready state, viewport, canonical URL,
description, and capture time. The ordered `elements()` collection contains meaningful controls,
headings, landmarks, and structured content in document order. Each element has a Phase 2
`ElementRole`, accessible name, bounded text, state, whitelisted attributes, capabilities, safe
value metadata, and a portable `ElementReference`.

Convenience views include `headings()`, `links()`, `buttons()`, `interactiveElements()`,
`landmarks()`, `forms()`, `navigations()`, `tables()`, `lists()`, `images()`, `dialogs()`, `alerts()`,
`tabLists()`, and `menus()`. Forms preserve field and submit ownership. Navigation regions preserve
owned links and `aria-current`. Tables and lists retain bounded samples plus original counts. The
semantic tree represents important containment, not every DOM edge.

An observed reference contains immutable locator intent rather than a browser handle. Resolve it
against the current page or pass it directly to the action API:

```java
SemanticElement submit = observation.buttons().getFirst();
IElement currentElement = submit.reference().resolve(page);
ActionResult<Void> result = page.action().click(submit.reference()).execute();
```

References re-locate before use. They do not promise that the live page is unchanged since the
observation.

## Options and budgets

Secure standard defaults use a five-second global deadline, exclude hidden elements and input
values, and bound elements, tree depth, text, table rows and columns, list items, and select options.

```java
ObservationOptions options = ObservationOptions.builder()
        .includeHidden(false)
        .includeInputValues(false)
        .timeout(Duration.ofSeconds(3))
        .maxElements(300)
        .maxDepth(8)
        .maxTableRows(50)
        .allowedDataAttributes(List.of("data-testid"))
        .build();

Observation observation = page.observe(options);
```

Applied limits are never silent. `ObservationStatistics.truncations()` identifies element, text,
tree, table, list, and option truncation with retained and original counts. Statistics also report
visited, included, filtered, and interactive counts and total duration. Timeout and interruption
fail with observation-specific exceptions. Snapshot capture, statistics, and completion-event
elapsed durations reject negative values.

`ObservationEngine` retains no per-observation mutable state and does not retain pages or live
elements between calls. Sharing one engine concurrently is safe only when every injected
collaborator (clock, identifier supplier, policies, factories, resolver, and listener) is also safe
for concurrent use. A page or frame remains caller-confined and must not be observed concurrently
unless its own implementation explicitly promises that behavior.

## Redaction

Input values are opt-in. Passwords, common token/API-key controls, and credit-card controls are
always redacted even when ordinary values are enabled. A redacted `ObservedValue` stores only its
disposition and whether a value existed; the source string never enters `PageSnapshot`,
`Observation`, renderers, events, warnings, or logs. Custom backends must enforce the same SPI
invariant.

Only semantic attributes and explicitly allowed `data-*` attributes are retained. Observation does
not copy full HTML, scripts, styles, cookies, storage, response bodies, image bytes, or arbitrary
attributes.

## Representations, fingerprint, and diff

`toCompactText()` emits a small, stable tree intended for diagnostics and future decision systems.
`toJson()` emits valid deterministic JSON containing the public semantic model, explicit
truncations, and safe values. It omits locator implementations, browser handles, backend identities,
and stable matching keys.

`fingerprint()` is SHA-256 over stable semantic metadata, ordered elements, safe states, and
relationships. Observation ID, capture timestamp, duration, and local indices do not affect it.

```java
Observation before = page.observe();
// The application changes the page.
Observation after = page.observe();
ObservationDiff diff = before.diff(after);
```

The MVP diff reports added, removed, and changed semantic elements, URL/title changes, and opened or
closed dialogs. It matches persistent semantic identity first and stable locator evidence second; it
does not compare raw DOM.

## Limitations

- Observation is passive but not an atomic browser transaction. A mutation detected during capture
  produces a warning.
- Shadow DOM and iframe traversal are not part of the Phase 3 MVP.
- Accessible-name extraction is intentionally deterministic and covers the supported native/ARIA
  surface; it is not a complete browser accessibility-tree implementation.
- Tables and lists are summaries, not data-extraction APIs.
- Warnings are factual semantic signals, not a WCAG audit.
- A local element index is valid only inside the observation that created it.
