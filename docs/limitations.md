# Known limitations

WebAgent4J is a deterministic semantic automation foundation, not a universal visual agent. Its safe
behavior depends on machine-readable browser semantics and current backend capabilities.

## Document boundaries

- Page and element scopes are supported. Frame scope exists in the model, but there is no public
  iframe traversal terminal operation yet.
- Playwright semantic selectors can traverse supported open shadow roots. Closed shadow roots are not
  inspectable, and explicit XPath has Playwright's usual shadow-DOM limitations.
- Native and correctly authored ARIA controls work best. Custom controls require a valid role,
  accessible name, state, and keyboard or pointer behavior.
- Invalid or missing ARIA degrades to the semantics the browser can expose. It never authorizes a
  guess based only on styling.

## Interfaces without reliable semantics

Canvas applications, remote-desktop streams, image maps without alternatives, pseudo-element-only
labels, inaccessible icon controls, and interfaces whose meaning exists only in color or position may
be `UNRESOLVABLE`. WebAgent4J has no OCR, computer-vision, or visual-coordinate fallback.

Exact role and accessible-name queries cannot distinguish duplicate controls with identical semantic
context. Use an element scope or add an accessible distinction; otherwise `single()` intentionally
returns `AMBIGUOUS`. Conservative fuzzy matching is not a language model and may reject short,
misspelled, multilingual, or closely related action text.

## Dynamic interaction

Semantic references re-resolve replacement nodes, and bounded waits handle delayed insertion. A page
can still mutate between final resolution and browser input. Interactability checks use attachment,
visibility, enabled state, viewport geometry, pointer events, and center-point coverage, but the browser
remains the final authority during animations, overlays, navigation, or removal races.

The deterministic benchmark currently gates Chromium. Standard CI and nightly jobs cover supported
operating systems, while broader Firefox and WebKit robustness matrices can be introduced after their
results and runtime are stable enough to be non-flaky.

## Explicit exclusions

WebAgent4J does not bypass CAPTCHA, authentication, anti-bot systems, access controls, or consent. It
does not rotate proxies or disguise browser fingerprints. Public-site tests, transactions, purchases,
bookings, comments, messages, or destructive forms are not part of the deterministic regression suite.

There is no AI, extraction engine, crawler, workflow engine, OCR, or visual recognition implementation
in this phase. Future optional fallbacks must preserve explicit uncertainty and action safety.
