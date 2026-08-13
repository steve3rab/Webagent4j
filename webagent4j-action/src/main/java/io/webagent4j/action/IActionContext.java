package io.webagent4j.action;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ILocatorResolver;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.observation.Observation;
import io.webagent4j.verification.IVerificationContext;

/** Runtime state made available to an action without exposing a browser backend. */
public interface IActionContext extends IVerificationContext, ILocatorResolver<IElement> {

    /** Returns the backend-neutral operations for the current page. */
    default IActionBackend actionBackend() {
        throw new UnsupportedOperationException("This context cannot execute browser actions");
    }

    /**
     * Resolves portable semantic references when supported by the runtime context.
     *
     * <p>Browser pages override this method. Minimal verification-only contexts remain compatible.
     */
    @Override
    default IElement resolve(LocatorDefinition definition) {
        throw new UnsupportedOperationException("This action context cannot resolve locators");
    }

    /** Captures a semantic observation when supported by the runtime page. */
    @Override
    default Observation observe() {
        throw new UnsupportedOperationException("This action context cannot observe pages");
    }
}
