/**
 * Deterministic, side-effect-free, pollable conditions over current page state - used both as
 * action postconditions ({@code IPreparedAction#expect(...)}) and standalone.
 *
 * <p>{@link io.webagent4j.verification.Verifications} is the primary entry point: static factories
 * for URL, title, element-state, element-data, and semantic-diff conditions, plus {@code allOf}/
 * {@code anyOf}/{@code not} composition. {@link io.webagent4j.verification.IVerification} is the
 * extension point applications implement for a custom condition; a condition only ever checks
 * whether it holds at the current polled instant - it never re-triggers the action that preceded
 * it. {@link io.webagent4j.verification.VerificationEngine} and {@link
 * io.webagent4j.verification.VerificationPoller} poll a condition (or a list of them) against a
 * {@link io.webagent4j.wait.WaitEngine}-driven budget, optionally the exact same shared budget an
 * action's other pipeline stages already draw from.
 *
 * <p>This package owns read-only conditions and polling only; it cannot depend on Playwright or
 * another concrete browser backend. See {@code docs/verification.md} for the full list of built-in
 * conditions and the precise scope of what a satisfied verification actually guarantees.
 */
package io.webagent4j.verification;
