/**
 * Verified browser actions: one explicit, bounded command pipeline per interaction, with three
 * terminal modes - real execution, {@code dryRun()}, and {@code plan()} - that all share the exact
 * same target-resolution and precondition logic so they can never disagree.
 *
 * <p>{@link io.webagent4j.action.IActionBuilder} is the entry point (reached through {@code
 * IPage#action()}/{@code IFrame#action()} in {@code webagent4j-browser-api}), producing an {@link
 * io.webagent4j.action.IPreparedAction} for one command. {@code execute()} runs the backend exactly
 * once and returns an {@link io.webagent4j.action.ActionResult}; {@code dryRun()} validates
 * resolution and preconditions without ever invoking the backend; {@code plan()} returns an
 * inspectable, single-use {@link io.webagent4j.action.IActionPlan} whose {@code execute()} reruns
 * the whole pipeline from scratch against live state rather than trusting the snapshot it was built
 * from.
 *
 * <p>{@link io.webagent4j.action.IActionBackend} is the extension point a browser backend
 * implements to actually perform each command; every default method throws {@link
 * java.lang.UnsupportedOperationException} unless overridden. This package owns orchestration only
 * - no browser-native implementation - and cannot depend on Playwright or any other concrete
 * backend. See {@code docs/actions.md} for every supported command, the retry-safety rules, and the
 * frame-scoped action contract.
 */
package io.webagent4j.action;
