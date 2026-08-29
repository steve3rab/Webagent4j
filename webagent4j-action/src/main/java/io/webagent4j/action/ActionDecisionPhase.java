package io.webagent4j.action;

/**
 * When, relative to the backend call, a governed-execution decision was made. Deliberately generic
 * across both {@link ActionDecisionKind} values, rather than reusing {@code NetworkCheckPhase}
 * directly, since this taxonomy also covers action-policy decisions.
 */
public enum ActionDecisionPhase {

    /** Before the backend call. A {@code DENY} here means the backend was never invoked. */
    PRE_EXECUTION,

    /**
     * After the backend call already happened - only ever produced by a post-navigation network
     * check, since that is the only decision this framework cannot make before the side effect.
     */
    POST_EXECUTION
}
