# Actions

Actions are explicit commands with structured outcomes:

```java
ActionResult<Void> result = page.action()
        .click(element)
        .expectUrlContains("/complete")
        .execute();
```

`ActionResult` distinguishes expected execution or postcondition failures from exceptional API misuse.
It contains duration, immutable audit events, an optional structured failure, and an action-specific
value. A builder is single-use and bound to its page. V1 implements the complete verified-click path;
additional action types will be added only with tests and public documentation.
