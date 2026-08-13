package io.webagent4j.verification;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.observation.Observation;

/** Minimal page state exposed to deterministic verifications. */
public interface IVerificationContext {

    /** Returns the current page URL. */
    String url();

    /** Returns the current page title. */
    String title();

    /** Resolves one portable locator against current page state. */
    default IElement resolve(LocatorDefinition definition) {
        throw new UnsupportedOperationException("Element resolution is not available");
    }

    /** Captures a semantic page observation when supported by the context. */
    default Observation observe() {
        throw new UnsupportedOperationException("Observation capture is not available");
    }
}
