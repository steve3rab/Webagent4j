# Semantic locators

WebAgent4J locators describe user-facing intent and resolve it deterministically. Accessible roles,
names, labels, placeholder text, titles, alternative text, and visible text usually survive DOM and
styling changes better than raw selectors. CSS and XPath remain explicit low-level escape hatches.

The locator core contains no AI dependency and no backend-specific type. Playwright is one adapter
behind the generic backend port.

## Quick start

Start from `IPage.find()` and choose the most specific semantic contract available:

```java
IElement login = page.find()
        .button()
        .named("Sign in")
        .visible()
        .enabled()
        .single();

IElement email = page.find().textbox().labelled("Email address").single();
IElement search = page.find().searchbox().placeholder("Search documentation").first();
IElement navigation = page.find().navigation().named("Primary").single();
```

Convenience methods cover interactive controls, headings, lists, tables, images, forms, and
landmarks. `role(ElementRole)` supports programmatic role selection. Native backend semantics include
implicit roles, associated labels, nested accessible text, `aria-labelledby`, form-control states,
and landmarks.

Explicit DOM contracts are also available:

```java
IElement submit = page.find().id("submit").first();
IElement fixture = page.find().testId("checkout-submit").first();
IElement product = page.find().css(".product").first();
IElement legacySubmit = page.find().xpath("//button[@type='submit']").first();
```

Test ids are deliberate explicit constraints; they do not outrank accessible semantics unless they
are requested. CSS and XPath execute only their requested low-level strategy and never trigger fuzzy
fallback.

## Resolution pipeline

Every terminal operation executes the same bounded pipeline against the current DOM:

1. Build an immutable `LocatorDefinition` from the fluent query.
2. Ask the backend which strategies and state capabilities it supports.
3. Execute applicable deterministic strategies in their fixed phase and priority order.
4. Merge discoveries that share the same backend element identity and aggregate their evidence.
5. Apply mandatory positive and negative constraints.
6. Score accepted candidates from the complete evidence set.
7. Apply the stable deterministic comparator and ambiguity rules.
8. Return a result, wait for a fresh DOM resolution, or fail with structured diagnostics.

Fuzzy matching is a fallback phase. It runs only when the policy allows it and deterministic
discovery produced no candidate. A unique exact candidate at or above the early-stop confidence can
end deterministic discovery early.

Every terminal resolution has a formal outcome: `RESOLVED`, `AMBIGUOUS`, `UNRESOLVABLE`,
`NOT_INTERACTABLE`, or `TIMEOUT`. Successful `LocatorResult` values expose `RESOLVED`;
`AmbiguousLocatorException` and `LocatorNotFoundException` expose the corresponding safe failure
status while retaining structured diagnostics. `NOT_INTERACTABLE` means matching evidence was found
but rejected by a requested state constraint. `TIMEOUT` is reserved for an explicit bounded wait.

## Candidates, evidence, score, and confidence

A candidate has a backend identity, current live element, DOM order, primary strategy, evidence list,
score, confidence, constraint status, and interactability status. Identity is supplied by the backend;
text, CSS, Java object hashes, and mutable attributes are never used as identity substitutes.

Evidence records the match type, strategy, normalized requested value, normalized observed value,
similarity, exactness, and contribution. Discoveries of the same element are deduplicated before
ranking, while their independent evidence is retained. This means a button supported by role,
accessible name, and label evidence is stronger than three duplicate candidates.

Scoring is cumulative, centralized in `LocatorScoringConfig`, and clamped to `[0.0, 1.0]`.
Strategies report evidence but do not own weights. The configurable contributions are:

- exact role, accessible name, associated label, visible text, and explicit attribute;
- fuzzy text multiplied by its normalized similarity;
- visibility, enabled state, and reliable interactability preferences when those states are not hard
  constraints.

Score expresses the accumulated ranking evidence. Confidence expresses certainty in the match;
fuzzy confidence remains below equivalent exact confidence. Both values are present in
`LocatorResult`, candidates, diagnostics, and completion events where appropriate.

## Contextual resolution

Some pages contain repeated actions that share the same accessible name but belong to different semantic
regions such as product cards, forms, dialogs, or table rows. The locator engine therefore supports an
explicit scope context that narrows the candidate universe before the main target resolution runs.

```java
IElement addToCart = page.find()
        .within(
                page.find().region().named("Laptop B").single())
        .button()
        .named("Ajouter")
        .single();

IElement shippingContinue = page.find(
                InteractionContext.context().containingText("Shipping"))
        .button()
        .named("Continue")
        .single();

IElement addToCartAvailable = page.find(
                InteractionContext.context()
                        .containingText("Laptop B")
                        .containingText("Available"))
        .button()
        .named("Ajouter")
        .single();
```

A context is treated as a hard scope, not a scoring bonus. It is resolved before target selection and
must fail explicitly when the scope is missing or ambiguous instead of silently picking the wrong
candidate. The existing locator pipeline remains deterministic: exact semantic resolution, required
state constraints, candidate reduction by scope, then ranking and ambiguity detection.

Each `containingText(...)` constraint is resolved by accessible name first (aria-label,
aria-labelledby, associated landmark or heading name, etc.). Visible-text matching is used only as a
fallback, and only when accessible-name resolution demonstrably reports a typed "not found" outcome;
an ambiguous accessible-name match or a genuine backend/runtime failure is never retried under visible
text and always propagates unchanged - a context ambiguity or backend failure means resolution stops,
not that a different strategy silently takes over. Every configured `containingText(...)` constraint is
honored, in order, each one narrowing the scope produced by the previous one: with two constraints,
`.containingText("Laptop B").containingText("Available")` first narrows to the "Laptop B" region, then
narrows again to "Available" strictly inside that region.

Context-aware locators also work naturally with `IElement.find()`, which reuses the same location
scope and backend. This helps resolve repeated actions inside a specific form, dialog, table row, or
semantic landmark without relying on brittle CSS selectors.

`within(...)`/`inContext(...)` are typed, not `Object`-typed: `ILocator<E>` and `IFind<E>` each expose
`within(E)` for an explicit element scope and `within(ILocatorScope<E>)` for a structured scope with
optional element and containing-text constraints. `InteractionContext` implements
`ILocatorScope<IElement>`, so passing either an `IElement` or an `InteractionContext` resolves to the
matching typed overload at compile time; there is no runtime type check or `IllegalArgumentException`
for an unsupported scope type.

## Dynamic contextual resolution

An explicit element scope (`within(existingElement)`) and a structured scope
(`within(InteractionContext.context()...)`) resolve differently on purpose, even though neither is
applied at `within(...)` call time - see [Mixed scope ordering](#mixed-scope-ordering) below for why
that matters. The caller handed over a concrete node for the former, so once it is reached in the
chain there is nothing left to re-derive: it becomes the scope directly. A structured scope instead
carries a *definition* (its `containingText(...)` constraints), and that definition is never
collapsed into one resolved DOM node while the fluent chain is being built. It stays pending and is
re-resolved, in order, at every terminal operation - `first()`, `single()`, `all()`, and every
invocation of a `reference()`'s deferred `resolve()` - so the semantic region is re-evaluated against
the live DOM each time, not reused from whatever node it happened to match earlier:

```java
IElementReference<IElement> continueButton = page.find(
                InteractionContext.context().containingText("Shipping"))
        .button()
        .named("Continue")
        .reference();

// Later - possibly after the DOM changed - each of these re-resolves "Shipping" fresh:
page.action().click(continueButton).execute();
```

This makes a stale context impossible to act on silently:

- If "Shipping" still resolves to the same, or a semantically equivalent replacement, region, the
  click reaches the correct target.
- If a second "Shipping" region appeared since the reference was built, resolution now reports
  `AmbiguousLocatorException` (surfaced as `ActionFailureType.TARGET_AMBIGUOUS` through an action) -
  the two-region case is never silently resolved against whichever one matched first.
- If "Shipping" was removed, resolution reports `LocatorNotFoundException`
  (`ActionFailureType.TARGET_NOT_FOUND`) - it never falls through to matching "Continue" inside an
  unrelated region that replaced it, such as a "Billing" section that happens to contain an
  identically-named button.

The same mechanism protects `IActionPlan.execute()` (see [Plans](actions.md#plans)): because it reruns
the whole pipeline from scratch, including target resolution through the same `reference()`, a plan
built against a context that later became ambiguous or disappeared is blocked, while a plan built
against a context later replaced with the same semantics can still execute exactly once.

Re-resolving a structured scope costs one bounded lookup per `containingText(...)` constraint, each
under the configured resolution budget, in addition to the final target lookup - the same cost the
scope already had at `within(...)` time, now paid again on each retry attempt and on `execute()`
revalidation instead of once. There is currently no shared outer deadline propagated across nested
constraint lookups, so a resolution retry policy combined with several constraints multiplies, rather
than divides, the per-call timeout; keep constraint chains short and resolution retries bounded if
this matters for a page under heavy load.

## Mixed scope ordering

Chaining multiple `within(...)` calls - explicit element scopes, structured scopes, or a mix of both
- is strictly ordered: each scope narrows the one declared immediately before it, in exactly the
sequence the caller wrote them in.

```java
page.find()
        .within(productContext)
        .within(formElement)
        // ...
```

is not the same search as the reverse order:

```java
page.find()
        .within(formElement)
        .within(productContext)
        // ...
```

The first narrows to `productContext`, then to `formElement` *inside* it - `formElement` must be a
real descendant of `productContext` for this to resolve. The second narrows to `formElement` first,
then looks for `productContext` *inside* `formElement` - which fails explicitly if `productContext`
is actually an ancestor of `formElement`, rather than silently reusing whichever scope resolved first
or regrouping the chain by scope kind (all explicit scopes first, or all structured scopes first).
This holds however deep the chain goes, and however explicit and structured scopes are interleaved;
it also holds for `within(...)` called again on the `ILocator` returned by a role/name selector, not
just on the initial `IFind`. Declaration order is resolved fresh at every terminal operation, exactly
like a single structured scope - see [Dynamic contextual resolution](#dynamic-contextual-resolution)
above.

## Non-throwing lookup

`tryFind()` attempts a single unambiguous resolution and returns `Optional.empty()` only for a real
"not found" outcome - never for a genuine backend or runtime failure, which is always rethrown. The
underlying failure is classified through the typed `ILocatorFailure` contract, looking through a
bounded chain of wrapped causes: a `LocatorNotFoundException` wrapped by an unrelated
`RuntimeException` still yields `Optional.empty()`, while an `AmbiguousLocatorException` or an opaque
backend failure - wrapped or not - is always rethrown rather than silently reported as a missing
match.

## Hard constraints and preferences

Requested role, name, label, id, attribute, test id, and state predicates are mandatory. A candidate
that violates one is rejected rather than merely penalized. Negative intent is represented explicitly:
`hidden()`, `disabled()`, `notDisabled()`, and state booleans in a programmatic definition do not
silently become preferences.

When the query does not mandate state, visible, enabled, and reliably interactable candidates receive
small configurable preferences. This keeps useful elements ahead of hidden or inert duplicates without
making visibility equivalent to clickability.

## Deterministic ranking and ambiguity

The final comparator is stable and follows this order:

1. exact role evidence;
2. exact accessible-name evidence;
3. exact associated-label evidence;
4. other exact evidence;
5. visibility;
6. interactability;
7. accumulated score;
8. confidence;
9. global DOM order;
10. backend identity.

The same DOM and configuration therefore produce the same order and explanation. `all()` returns all
accepted, deduplicated candidates in that order. `first()` accepts multiple candidates and returns the
best one. `single()` also requires the best semantic tier to be unique: if the next candidate is within
the configured ambiguity margin, it throws `AmbiguousLocatorException` instead of hiding the conflict
with DOM order.

## Resolution policies

`LocatorResolutionPolicy` makes fallback behavior explicit:

- `STRICT` disables fuzzy fallback and defaults its fuzzy threshold to `1.0`.
- `BALANCED` is the default and uses conservative fuzzy fallback with threshold `0.80`.
- `PERMISSIVE` defaults to threshold `0.70` and forces detailed diagnostics so weaker decisions remain
  inspectable.

Thresholds, ambiguity margin, early stopping, locale, polling interval, scoring, budgets, diagnostics,
and test-id attribute are immutable configuration:

```java
LocatorConfig config = LocatorConfig.builder()
        .resolutionPolicy(LocatorResolutionPolicy.STRICT)
        .ambiguityMargin(0.01)
        .earlyStopConfidence(0.97)
        .diagnosticsLevel(LocatorDiagnosticsLevel.DETAILED)
        .locale(Locale.ROOT)
        .testIdAttribute("data-qa")
        .resolutionBudget(new LocatorResolutionBudget(
                Duration.ofSeconds(3), 75, 8, 25))
        .build();

IElement pay = page.find(config).button().named("Pay").single();
```

`LocatorConfig`, `LocatorResolutionBudget`, `LocatorScoringConfig`, definitions, results, candidates,
evidence, and diagnostics are immutable and thread-safe. Live pages and elements remain backend-bound
and are not thread-safe.

## Dynamic DOM and re-resolution

Fluent locators do not cache element handles. `first()`, `single()`, and `all()` resolve against the
current DOM. When a matching node is absent, the engine polls by executing a fresh bounded search plan
until success or timeout. It does not retain a stale candidate list.

A semantic reference makes this lifecycle explicit:

```java
IElementReference<IElement> save = page.find()
        .button()
        .named("Save")
        .visible()
        .reference();

IElement beforeReplacement = save.resolve();
// The application replaces the node.
IElement afterReplacement = save.resolve();

page.action().click(save).execute();
```

Each `resolve()` re-executes the definition. An action created from an `IElementReference` resolves
immediately before execution, so it targets the replacement element. An already returned `IElement`
is a live locator-backed, page-bound handle, but callers should use a reusable reference when node
replacement is expected between workflow steps.

`stableFor(duration)` requires the selected backend identity and all requested state constraints to
remain continuously satisfied for the whole interval. Detachment, replacement, disappearance, or a
state violation resets the stability timer; non-contiguous stable periods are never added together.

## State and interactability

The state model distinguishes:

- present, visible, enabled, editable, and read-only;
- checked and selected;
- focused and inside the current viewport;
- covered by another element and reliably clickable;
- whether the backend can determine interactability reliably.

These are separate properties. In particular, visible does not imply enabled, uncovered, in the
viewport, or clickable. `clickable()` is a hard constraint based on the injected interactability
checker and backend capabilities. The Playwright adapter checks attachment, computed visibility,
geometry, disabled/read-only state, viewport position, pointer events, and center-point coverage. The
browser action remains the final authority because animations or overlays can still race after
resolution.

Backends that cannot reliably determine a requested state report that capability. They must not invent
clickability by mapping it to visibility. Diagnostics show capability-based strategy skips and state
rejections.

## Scope

Element scopes reuse the same context, configuration, strategy plan, filters, scorer, budgets, and
diagnostics:

```java
IElement form = page.find().form().named("Payment").single();
IElement pay = form.find().button().named("Pay").single();
```

Diagnostics retain the hierarchical scope path. `PAGE` and `ELEMENT` scopes are implemented.
`FRAME` is represented in the scope model for future adapters but is not currently exposed as a
terminal public operation. The Playwright adapter follows native open-shadow-root behavior where the
selected strategy supports it; explicit XPath retains Playwright's normal shadow-DOM limitations.

## Text normalization

Text comparison performs Unicode NFKC normalization, converts non-breaking whitespace, collapses
whitespace, trims, and applies explicit-locale case folding. `Locale.ROOT` is the deterministic default.
Accents are preserved, so `resume` is not silently treated as the exact form of `résumé`.

`fuzzyName()` first attempts exact accessible-name, label, and visible-text evidence. Only then can the
conservative non-AI similarity matcher run, subject to the policy, threshold, and fuzzy-candidate
budget. Weak similarities remain rejected.

An explicit `aria-label` or valid `aria-labelledby` name is authoritative. Contradictory visible text
does not override it during deterministic or fuzzy fallback. Fuzzy similarity compares the complete
phrase, treats common negating prefixes conservatively, and intentionally returns ambiguity or no
result for close action lookalikes.

## Budgets and performance

`LocatorResolutionBudget` bounds total duration, accepted/discovered candidate work, executed
strategies, and fuzzy candidates. Backend searches receive both remaining time and candidate limits,
so a large DOM cannot create an unbounded Java candidate list. Diagnostics retain bounded rejection
details instead of thousands of entries.

Prefer a specific role plus accessible name over broad text, CSS, or XPath scans. Exact unique results
can stop early; fuzzy discovery is skipped whenever deterministic discovery succeeds. A timeout is a
global deadline across polling, strategy execution, scoring, ambiguity checks, and stability—not a new
timeout for each phase.

## Diagnostics, events, and logging

Programmatic resolution exposes immutable machine-readable diagnostics:

```java
import static io.webagent4j.locator.api.LocatorDefinitions.element;

LocatorResult result = page.locate(
        element().role(ElementRole.BUTTON).named("Sign in"), config);

LocatorDiagnostics diagnostics = result.diagnostics();
System.out.println(result.explain());
```

Diagnostics include the requested definition, policy, level, scope path, executed and skipped
strategies, per-strategy duration and truncation, discovered/deduplicated/rejected counts, applied
filters, exact/fuzzy counts, selected candidate, total duration, budget limits, ambiguity data, and
bounded rejected-candidate details. `LocatorDiagnosticsRenderer` produces the stable human explanation;
the structured model is the source of truth. `OFF`, `BASIC`, and `DETAILED` control retained detail.
Locator exceptions retain the structured diagnostics when available.

An injected `ILocatorEventListener` can receive `ResolutionStarted`, `StrategyExecuted`,
`CandidateFound`, `ResolutionCompleted`, and `ResolutionFailed`. There is no mutable global event bus.
DEBUG and TRACE logs expose resolution flow and counts without logging raw requested or observed
locator values, which may contain secrets.

## Custom strategies

`ILocatorStrategy` is the extension point. Each strategy declares a stable id, phase, priority,
supported definitions, and discovery implementation. A `LocatorStrategyRegistry` is assembled at
bootstrap from standard and application strategies; it is immutable and has no global singleton.

Standard exact, selector, and fallback phases remain authoritative. Custom priority orders custom
strategies within their phase but cannot reorder the standard semantic plan. Backend capabilities can
skip unsupported strategies explicitly. Future AI, visual, or self-healing plugins can integrate
through these ports without changing `LocatorDefinition`, `LocatorEngine`, `IPage`, or `IElement`, but
none is present in or required by the deterministic core.
