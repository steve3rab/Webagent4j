package io.webagent4j.action;

/** Which governed-execution gate an {@link ActionDecisionEntry} records a decision from. */
public enum ActionDecisionKind {

    /** An {@code IActionPolicy} decision - see {@code io.webagent4j.action.policy}. */
    ACTION,

    /** An {@code INetworkPolicy} decision - see {@code io.webagent4j.policy.network}. */
    NETWORK
}
