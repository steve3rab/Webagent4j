package io.webagent4j.action.policy;

import io.webagent4j.policy.IExecutionPolicy;

/**
 * Authorizes one action before its backend side effect is invoked. See {@link IExecutionPolicy} for
 * the full evaluation contract (synchronous only, fail-closed on the caller's side, no hidden
 * retry, two outcomes only).
 *
 * <p>Configured on a prepared action via {@code IPreparedAction#policy(IActionPolicy)}. See {@code
 * docs/governed-execution.md} for the exact pipeline position this is evaluated at and what happens
 * on {@code DENY} or evaluation failure.
 */
@FunctionalInterface
public interface IActionPolicy extends IExecutionPolicy<ActionPolicyContext> {}
