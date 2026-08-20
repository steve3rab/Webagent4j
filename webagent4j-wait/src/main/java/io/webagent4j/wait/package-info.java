/**
 * The one deterministic polling/deadline primitive shared by locator resolution, verification
 * polling, and action stabilization - not three independent timers.
 *
 * <p>{@link io.webagent4j.wait.WaitEngine#await(io.webagent4j.wait.WaitBudget,
 * io.webagent4j.wait.WaitPolicy, io.webagent4j.wait.IWaitProbe)} owns exactly four things: when to
 * poll, when to stop, how much time remains, and when a satisfied result has been stable long
 * enough. It owns nothing about <em>what</em> is being waited for - that is entirely the caller's
 * {@link io.webagent4j.wait.IWaitProbe}, a side-effect-free reading. {@link
 * io.webagent4j.wait.WaitBudget} is a monotonic, saturating deadline that can be shared across
 * sequential sub-operations, each seeing a shrinking {@code remaining()} rather than a fresh
 * timeout. {@link io.webagent4j.wait.IMonotonicClock} is never measured against wall-clock time,
 * which can jump on an NTP correction or daylight-saving change.
 *
 * <p>This package knows nothing about DOM elements, locators, or browser actions; those domains
 * ({@code webagent4j-locator}, {@code webagent4j-verification}, {@code webagent4j-action}) are
 * adapters built on top of it. See {@code docs/wait-and-stability.md} for the full per-domain
 * breakdown and the exactly-once backend-execution guarantee this engine never compromises.
 */
package io.webagent4j.wait;
