package io.webagent4j.locator;

import io.webagent4j.dom.IElement;

/** Specialized service that evaluates whether an element can reliably receive an action. */
@FunctionalInterface
public interface IInteractabilityChecker {

    /** Returns an explainable interactability decision for the element's current state. */
    InteractabilityResult check(IElement element);
}
