/**
 * Action authorization: {@link io.webagent4j.action.policy.IActionPolicy} governs whether one
 * action's backend side effect may proceed, evaluated by the action pipeline strictly before the
 * backend is ever invoked.
 *
 * <p>This is one of two independent governed-execution gates - the other, network-destination
 * governance, lives in {@code io.webagent4j.policy.network} ({@code webagent4j-common}) and is
 * wired into {@code NAVIGATE} actions and the crawler modules. Both gates must pass; neither
 * implies the other. See {@code docs/governed-execution.md}.
 */
package io.webagent4j.action.policy;
