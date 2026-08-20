/**
 * The backend-neutral live-element vocabulary shared by the locator, action, extraction, and
 * observation subsystems - depends only on immutable, backend-neutral contracts.
 *
 * <p>{@link io.webagent4j.dom.IElement} is a live reference to one resolved element: role, name,
 * text, tag, attributes, state predicates, {@code click()}, a scoped {@code find()} for nested
 * queries, and {@code extract(ExtractionRequest)} for reading directly off an already-resolved
 * element with no further search. {@link io.webagent4j.dom.ElementState} is the immutable
 * interactability snapshot (present, visible, enabled, editable, checked, focused, clickable,
 * covered, and whether the backend can determine interactability reliably at all) that {@code
 * IElement}'s state predicates delegate to. {@link io.webagent4j.dom.BoundingBox} is an element's
 * geometry in CSS pixels.
 *
 * <p>This package depends on {@code webagent4j-extraction-api} for exactly one method - {@code
 * IElement#extract(ExtractionRequest)} - never the reverse; that one-directional edge is enforced
 * by an ArchUnit rule. See {@code docs/locators.md} for how a live {@code IElement} is obtained and
 * re-resolved.
 */
package io.webagent4j.dom;
