/**
 * Generic, synchronous execution-policy contracts shared across WebAgent4J modules.
 *
 * <p>This package defines the vocabulary every governed-execution gate is built from: {@link
 * io.webagent4j.policy.IExecutionPolicy}, a pure function from an immutable context to a {@link
 * io.webagent4j.policy.PolicyDecision}. There are exactly two possible outcomes ({@link
 * io.webagent4j.policy.PolicyOutcome#ALLOW} or {@link io.webagent4j.policy.PolicyOutcome#DENY}) -
 * no {@code UNKNOWN}, {@code ASK}, or deferred outcome exists, so a caller can never be left
 * uncertain about whether a side effect may proceed.
 *
 * <p>Nothing in this package performs I/O, blocks, retries, or calls out to a remote service (HTTP,
 * MCP, an LLM, a database). Evaluation is synchronous, in-process, and deterministic given its
 * input - callers that need those things build them into their own {@link
 * io.webagent4j.policy.IExecutionPolicy} implementation, which this package treats as ordinary,
 * untrusted, unsandboxed Java code.
 *
 * <p>Action-specific and network-specific policy contracts live in {@code io.webagent4j.action}
 * (action authorization, {@code webagent4j-action}) and {@code io.webagent4j.policy.network}
 * (network-destination governance, this module) respectively, both built on the generic contracts
 * defined here.
 */
package io.webagent4j.policy;
