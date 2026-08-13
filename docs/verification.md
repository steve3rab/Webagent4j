# Verification

`IVerification` is a deterministic, side-effect-free condition over `IVerificationContext`.
`VerificationEngine` polls it with a positive interval and timeout, returning a structured
`VerificationResult`. Conditions do not hide mismatches in exceptions.

```java
ActionResult<Void> result = page.action()
        .click(page.find().button().named("Continue").reference())
        .expect(allOf(
                urlContains("/complete"),
                titleContains("Complete"),
                textVisible("Order confirmed")))
        .execute();
```

## Built-in conditions

- URL: `urlContains`, `urlEquals`, and `urlMatches`
- Page: `titleEquals`, `titleContains`, and `textVisible`
- Element state: exists, missing, visible, hidden, enabled, disabled, editable, checked, unchecked,
  selected, and focused
- Element data: exact text, contained text, attribute value, input value, and element count
- Semantic diff: element added, element removed, dialog opened, and any state changed
- Composition: `allOf`, `anyOf`, and `not`

Element conditions use reusable semantic references, so each poll observes the current DOM rather
than a stale native handle. `valueEquals(String)` can be attached directly to a target action and is
bound to that target by the action engine.

## Polling and timeout behavior

The first evaluation is immediate. Failed conditions are evaluated again at the configured interval
until success or timeout. The interval is capped by the remaining budget, and the final result records
its duration and timeout state. Polling does not repeat the browser action that preceded it.

Composite conditions preserve encounter order. `allOf` succeeds only when every child succeeds;
`anyOf` succeeds when at least one child succeeds; `not` negates one child. Conditions should remain
fast, deterministic, read-only, and safe to evaluate repeatedly.

## Custom conditions

Applications may implement `IVerification` against the small public context. Return expected and
actual values that are useful but safe to retain. Never include passwords, authorization headers,
tokens, payment data, or unrestricted page content in a result. Use semantic observations when a
condition depends on page-wide state.

Verification definitions and results are immutable and thread-safe when their custom predicates are
thread-safe. A live page context remains confined to its owning browser thread.

See [Actions](actions.md) for lifecycle, retry safety, and failure mapping.
