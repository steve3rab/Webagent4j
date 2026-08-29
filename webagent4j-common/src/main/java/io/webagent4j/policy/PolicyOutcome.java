package io.webagent4j.policy;

/**
 * The result of one policy evaluation. Deliberately exhaustive: there is no {@code UNKNOWN}, {@code
 * ASK}, or deferred value. A policy that cannot reach a confident decision must throw rather than
 * return an ambiguous outcome - see {@link IExecutionPolicy} - so that uncertainty is never
 * silently interpreted as permission.
 */
public enum PolicyOutcome {

    /** The evaluated action or destination may proceed. */
    ALLOW,

    /** The evaluated action or destination must not proceed. */
    DENY
}
