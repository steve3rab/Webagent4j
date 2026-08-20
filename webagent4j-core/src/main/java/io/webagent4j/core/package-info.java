/**
 * WebAgent4J's public facade: the entry point application code starts from.
 *
 * <p>{@link io.webagent4j.core.WebAgent#browser()} is the only way to obtain a {@link
 * io.webagent4j.core.BrowserBuilder}, which discovers a concrete {@link
 * io.webagent4j.browser.IBrowserProvider} at runtime through {@link java.util.ServiceLoader} - this
 * module never depends on {@code webagent4j-browser-playwright} (or any other backend) at compile
 * time, so it stays backend-neutral by construction rather than by convention.
 *
 * <p>Everything reachable from the resulting {@link io.webagent4j.browser.IBrowser} - pages,
 * frames, locators, observations, actions, verification, and extraction - is defined in {@code
 * webagent4j-browser-api} and its domain modules; this package owns only the launch step. See
 * {@code docs/public-api.md} for the full map of modules and entry points.
 */
package io.webagent4j.core;
