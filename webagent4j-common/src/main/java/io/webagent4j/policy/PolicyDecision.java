package io.webagent4j.policy;

import java.util.Objects;

/**
 * An immutable, terminal policy decision: an {@link PolicyOutcome} paired with the {@link
 * PolicyReason} it was reached for. A reason is always required, for both {@link
 * PolicyOutcome#ALLOW} and {@link PolicyOutcome#DENY} - an unexplained allow is exactly as unsafe
 * to audit as an unexplained deny.
 *
 * <p>{@link #toString()} is deliberately narrow and safe to log: it renders only the outcome and
 * the reason code, never caller-supplied context (a URI, a locator, secret text). Callers that need
 * the full evaluated context for diagnostics must capture it themselves, outside this type.
 */
public record PolicyDecision(PolicyOutcome outcome, PolicyReason reason) {

    /** Validates that both fields are present. */
    public PolicyDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
    }

    /** Creates an {@link PolicyOutcome#ALLOW} decision with the given reason. */
    public static PolicyDecision allow(PolicyReason reason) {
        return new PolicyDecision(PolicyOutcome.ALLOW, reason);
    }

    /** Creates an {@link PolicyOutcome#ALLOW} decision with the given reason code. */
    public static PolicyDecision allow(String reasonCode) {
        return allow(PolicyReason.of(reasonCode));
    }

    /** Creates an {@link PolicyOutcome#DENY} decision with the given reason. */
    public static PolicyDecision deny(PolicyReason reason) {
        return new PolicyDecision(PolicyOutcome.DENY, reason);
    }

    /** Creates an {@link PolicyOutcome#DENY} decision with the given reason code. */
    public static PolicyDecision deny(String reasonCode) {
        return deny(PolicyReason.of(reasonCode));
    }

    /** Returns whether this decision is {@link PolicyOutcome#ALLOW}. */
    public boolean isAllow() {
        return outcome == PolicyOutcome.ALLOW;
    }

    /** Returns whether this decision is {@link PolicyOutcome#DENY}. */
    public boolean isDeny() {
        return outcome == PolicyOutcome.DENY;
    }

    /** Renders only the outcome and reason code - safe to log unconditionally. */
    @Override
    public String toString() {
        return "PolicyDecision[outcome=" + outcome + ", reason=" + reason.code() + "]";
    }
}
