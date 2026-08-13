package io.webagent4j.action;

/** Conservative execution retry classification. */
public enum ActionIdempotency {
    IDEMPOTENT,
    CONDITIONALLY_IDEMPOTENT,
    NON_IDEMPOTENT
}
