# Semantic locators and scopes

WebAgent4J locators express user-facing intent and resolve it deterministically against the live page. Semantic evidence is preferred over brittle DOM position. CSS and XPath remain explicit low-level escape hatches rather than hidden fallbacks.

## Quick start

```java
IElement login = page.find()
        .button()
        .named("Sign in")
        .visible()
        .enabled()
        .single();

IElement email = page.find().textbox().labelled("Email address").single();
IElement search = page.find().searchbox().placeholder("Search").first();
```

Explicit selectors are available when semantic intent is not appropriate:

```java
IElement byId = page.find().id("submit").first();
IElement byTestId = page.find().testId("checkout-submit").first();
IElement byCss = page.find().css(".product").first();
IElement byXpath = page.find().xpath("//button[@type='submit']").first();
```

Test IDs are explicit constraints. CSS/XPath do not silently switch to semantic or fuzzy strategies.

## Resolution pipeline

Every terminal operation follows the same bounded shape:

1. build an immutable `LocatorDefinition`;
2. resolve the current live context/scope;
3. execute applicable strategies in deterministic phase/priority/ID order;
4. merge discoveries referring to the same backend identity;
5. apply mandatory positive/negative constraints;
6. score accepted candidates from evidence;
7. apply deterministic ordering and ambiguity rules;
8. return, poll within the existing budget, or fail with typed diagnostics.

Fuzzy matching is a fallback policy, not a first-class guess. It runs only when the configured policy permits it and deterministic discovery does not already justify a result.

## Terminal operations

- `all()` returns all accepted candidates in deterministic rank order.
- `first()` accepts multiplicity and returns the best ranked candidate.
- `single()` requires the best semantic tier to be unique. A competing candidate within the ambiguity rule throws `AmbiguousLocatorException` instead of using DOM order as a hidden tie-break.
- `tryFind()` returns `Optional.empty()` only for a real typed “not found” outcome. Ambiguity and genuine backend/runtime failures propagate.
- `reference()` creates reusable semantic intent that re-resolves later rather than storing a fixed native handle.

Ambiguity is fail-safe. During a waiting `single()`, ambiguity on any poll terminates immediately; the engine does not keep polling in the hope that a duplicate disappears.

## Evidence, score, and identity

Candidates carry backend identity, live element, DOM order, strategy/evidence, score, confidence, and state/interactability data. Multiple evidence sources for the same physical candidate are merged before ranking.

Backend identity is structural. Visible text, accessible name, mutable attributes, DOM index, Java object hash, and page-controlled JavaScript globals are not acceptable substitutes for physical identity.

Scores/confidence are finite normalized values. `NaN` is rejected rather than treated as a special ranking value.

## Hard constraints and preferences

Requested role/name/label/id/attribute/test-id/state predicates are hard constraints. A candidate violating one is rejected.

When state is not required, visibility/enabled/interactability can contribute ranking preferences according to configuration. A preference never turns a hard constraint failure into a valid candidate.

## Resolution policy

`LocatorResolutionPolicy` controls fuzzy fallback and diagnostic detail. Configuration also controls ambiguity margin, early-stop confidence, locale, polling interval, test-id attribute, scoring contributions, budgets, and diagnostic level.

Definitions/configuration/results/diagnostics are immutable. Live page/element objects remain backend-bound and caller-confined.

## Structured scopes

Repeated controls are disambiguated with a hard scope:

```java
IElement add = page.find(
        InteractionContext.context().containingText("Laptop B"))
    .button()
    .named("Add")
    .single();
```

A structured scope is resolved before the target and must itself be unambiguous. A missing scope does not broaden to the whole page. An ambiguous scope fails even if the final target would happen to be unique in one of the competing regions.

For `containingText(...)`, the Playwright path tries supported accessible-name semantics first and falls back to visible text only after typed accessible-name absence. Ambiguity or backend/runtime failure does not trigger a fallback.

Multiple `containingText` constraints narrow in declaration order. Mixed explicit-element and structured scopes also preserve declaration order.

## Dynamic scope identity

Structured scope definitions are re-resolved on every terminal operation and every poll of a waiting terminal operation. They are not reduced to a DOM index when the fluent chain is created.

For the Playwright adapter, an already-resolved structured scope additionally protects its physical identity across the classification/use seam:

- reordering the same live DOM element does not retarget it;
- a different physical node cannot impersonate the old one merely by taking its DOM position or copying its semantic text;
- unrelated later scope resolutions do not expire a valid old scope through an arbitrary retention cap;
- a fresh semantic duplicate introduced later still surfaces as ambiguity before the physical guard is consulted;
- application JavaScript cannot choose candidate identities through mutable attributes or known global variables;
- identity bookkeeping does not mutate the application DOM.

This is a safety property for a live already-bound scope, not a promise that every old element remains valid after physical replacement. When replacement is expected between independent operations, use a semantic `reference()` so the new operation can intentionally resolve the current semantic node.

## Mixed explicit scopes

`within(A).within(B)` means B must be B **inside A**, not “use whichever scope is more convenient”. For an explicit element nested after an existing scope, containment is proven against the real DOM. If the child was moved elsewhere before the terminal operation, the chain fails rather than silently substituting it.

## Frames

`IPage#frame()` / `IFrame#frame()` build frame criteria and use 0/1/N semantics. Frame criteria include supported `id`, `name`, `title`, and URL matching modes. There is intentionally no `first()` terminal for frames: DOM order is not a hidden disambiguation rule.

A URL criterion participates in filtering before the final ambiguity classification, so it can legitimately disambiguate otherwise equal frame name/title criteria. Opaque failures while inspecting a frame URL propagate; only proven frame disappearance is treated as absence for the current poll.

Nested and cross-origin frames are entered through backend-neutral `IFrame`. Browser security is not weakened and no native Playwright frame type escapes the API.

## Shadow DOM

Supported Playwright semantic selectors can cross open shadow roots where the native strategy supports them. Closed shadow roots are not inspectable. Explicit XPath keeps Playwright's normal shadow-DOM limitations.

## Wait and stability

Fluent locator operations re-query the current DOM rather than caching a fixed candidate list. `stableFor(duration)` requires the selected backend identity and requested states to remain continuously satisfied for the full window. Detachment, replacement, disappearance, state failure, or a changed stability key resets the window.

Locator waits share `WaitEngine`/`WaitBudget` with the rest of the framework. Structured-scope re-resolution consumes the same outer budget; it does not start nested full-duration waits for every scope constraint.

## Backend failure classification

A timeout or Playwright race is not automatically “not found”. Where the adapter can encounter a disappearance race, it converts the condition to absence only after a fresh current-state check proves the candidate/frame is gone. A still-present target or an opaque/failed recheck preserves the original backend error.

See [Cross-module contracts](contracts.md#identity-and-ambiguity), [Wait and stability](wait-and-stability.md), and [Security model](security-model.md#semantic-target-safety).
