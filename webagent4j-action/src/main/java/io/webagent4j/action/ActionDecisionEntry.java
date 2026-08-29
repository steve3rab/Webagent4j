package io.webagent4j.action;

import io.webagent4j.policy.PolicyReason;
import java.util.Objects;

/**
 * One governed-execution decision recorded during an action's pipeline.
 *
 * <p>Deliberately narrow, and always safe to render: {@link #reason()} is a validated {@link
 * PolicyReason} (a stable, grammar-restricted code, never free-form or caller-supplied text), and
 * nothing else about the evaluated context - a URI, a locator, secret text - is ever carried here.
 *
 * @param kind which gate produced this decision
 * @param phase when this decision was made, relative to the backend call
 * @param outcome what the decision was
 * @param reason the stable reason code - the denying policy's own reason on {@code DENY}, a
 *     built-in code on {@code EVALUATION_FAILED}, or the allowing policy's own reason on {@code
 *     ALLOW}
 */
public record ActionDecisionEntry(
        ActionDecisionKind kind,
        ActionDecisionPhase phase,
        ActionDecisionOutcome outcome,
        PolicyReason reason) {

    /** Validates that every field is present. */
    public ActionDecisionEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
    }
}
