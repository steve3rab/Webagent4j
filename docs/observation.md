# Semantic observation

Observation captures a passive, bounded semantic snapshot without exposing the raw DOM. `page.observe()` returns a detached immutable `Observation`; reading it later never queries the live page again.

## Model

An observation contains page metadata, ordered semantic elements, states, whitelisted attributes, capabilities, bounded text, semantic relationships, warnings/truncation statistics, and portable locator intent.

Convenience views cover headings, links, buttons, interactive elements, landmarks, forms, navigation regions, tables, lists, images, dialogs, alerts, tab lists, and menus.

An observed reference represents semantic intent, not a native browser handle:

```java
SemanticElement submit = observation.buttons().getFirst();
IElement live = submit.reference().resolve(page);
page.action().click(submit.reference()).execute();
```

Re-resolution does not promise that the page stayed unchanged since capture.

## Bounds

Observation defaults are conservative and bound global time, element count, text, tree depth, table/list samples, and option samples. Hidden elements and ordinary input values are excluded by default.

Applied bounds are explicit in `ObservationStatistics.truncations()`. Truncation is data, not a silent assumption that the omitted tail did not exist.

## Redaction

Input values are opt-in. Passwords, common token/API-key controls, and payment-card controls remain redacted even when ordinary values are requested. A redacted observed value retains only disposition/presence information; the raw source string does not enter the semantic snapshot or framework-owned renderers.

Only semantic attributes and caller-allowed `data-*` attributes are retained. Observation does not copy arbitrary HTML attributes, scripts, styles, cookies, storage, response bodies, image bytes, or the full DOM.

## Rendering, fingerprint, diff

- `toCompactText()` is a small deterministic semantic representation intended for diagnostics/decision systems.
- `toJson()` serializes the public semantic model and explicit truncation/safe-value state; it is not a persistence compatibility format equivalent to Recording V1.
- `fingerprint()` hashes stable semantic content and excludes incidental capture identity/time/duration/index fields.
- `diff()` reports semantic additions/removals/changes and selected page/dialog changes; it does not compare raw DOM trees.

## Frames and document boundaries

Observation operates on the page or explicitly resolved frame scope it is asked to observe. That is different from recursively traversing and combining every nested iframe into one observation. Automatic whole-frame-tree crawling is not implied by frame support in the browser/locator API.

## Concurrency

`ObservationEngine` retains no per-call mutable session state, but concurrent sharing is safe only when all injected collaborators are also concurrency-safe. A live page/frame remains caller-confined and must not be observed concurrently unless its implementation explicitly promises otherwise.

## Limitations

Observation is passive but not an atomic browser transaction. Page mutation can occur during capture and is handled through warnings/failures/bounds according to the implementation. Accessible-name modeling is deliberately deterministic but not a promise to reproduce every browser accessibility-tree implementation detail. Tables/lists are semantic summaries, not a replacement for [Extraction](extraction.md).
