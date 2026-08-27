/**
 * Immutable, backend-neutral locator definitions and the fluent query contracts every semantic
 * search builds on - {@link io.webagent4j.locator.api.IFind} and {@link
 * io.webagent4j.locator.api.ILocator} contain no engine logic and no backend-native type.
 *
 * <p>{@link io.webagent4j.locator.api.LocatorDefinition} is the immutable record a fluent query
 * ultimately assembles: role, accessible name, label, text, attribute, or an explicit {@code
 * css()}/{@code xpath()} escape hatch, plus optional timeout and stability requirements. {@link
 * io.webagent4j.locator.api.ILocatorScope} is the typed contract behind {@code within(...)}, so a
 * scope is checked at compile time rather than by a runtime {@code instanceof}. {@link
 * io.webagent4j.locator.api.IElementReference} and {@link
 * io.webagent4j.locator.api.ElementReference} are re-resolvable handles: resolving one re-executes
 * the definition against current state rather than returning a cached node.
 *
 * <p>The resolution engine that turns a {@code LocatorDefinition} into live elements lives in
 * {@code webagent4j-locator} ({@code io.webagent4j.locator.ILocatorEngine}, not in this module);
 * this module exists so {@code IElement#find()} and {@code IPage#find()} can be declared without a
 * dependency cycle. See {@code docs/locators.md} for the full resolution pipeline, ranking, and
 * ambiguity contract.
 */
package io.webagent4j.locator.api;
