package io.webagent4j.action;

/**
 * The outcome recorded for one governed-execution decision. Distinct from {@code
 * io.webagent4j.policy.PolicyOutcome} (which is only ever {@code ALLOW}/{@code DENY} for a decision
 * a policy actually produced) because a trace must also represent the case where no decision was
 * produced at all - a thrown exception or a malformed {@code null} result - without conflating it
 * with an intentional {@code DENY}.
 */
public enum ActionDecisionOutcome {

    /** The policy evaluated to {@code ALLOW}. */
    ALLOW,

    /** The policy evaluated to {@code DENY}. */
    DENY,

    /** The policy failed to produce a decision - it threw, or returned {@code null}. */
    EVALUATION_FAILED
}
