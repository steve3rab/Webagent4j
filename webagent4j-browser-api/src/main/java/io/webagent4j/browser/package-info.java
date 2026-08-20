/**
 * Backend-neutral browser, page, and frame contracts - no Playwright (or other native backend) type
 * appears in any public signature here.
 *
 * <p>Entry points: {@link io.webagent4j.browser.IBrowser} owns a browser process and isolated
 * context; {@link io.webagent4j.browser.IPage} is one tab, exposing navigation, {@link
 * io.webagent4j.observation.Observation}s, {@link io.webagent4j.locator.api.IFind locators}, {@link
 * io.webagent4j.action.IActionBuilder actions}, and extraction; {@link
 * io.webagent4j.browser.IFrame} mirrors {@code IPage}'s contract scoped to one {@code <iframe>}
 * document boundary and is re-resolved by semantic identity on every operation, never a frozen
 * snapshot - see its class documentation for the exact re-resolution contract. {@link
 * io.webagent4j.browser.IFrameLocator} resolves an {@code IFrame}, mirroring {@link
 * io.webagent4j.locator.api.ILocator} at the document-boundary level.
 *
 * <p>{@link io.webagent4j.browser.IBrowserProvider} is the extension point a backend module
 * (currently only {@code webagent4j-browser-playwright}) implements and registers through {@link
 * java.util.ServiceLoader}; application code obtains an implementation through {@code
 * WebAgent.browser()} (module {@code webagent4j-core}), never by referencing a provider directly.
 */
package io.webagent4j.browser;
