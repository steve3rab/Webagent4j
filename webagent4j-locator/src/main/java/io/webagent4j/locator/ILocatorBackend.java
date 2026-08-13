package io.webagent4j.locator;

import java.time.Duration;

/** Port implemented by browser adapters for bounded native candidate discovery. */
public interface ILocatorBackend {

    /** Declares supported strategies and advanced state capabilities. */
    default LocatorBackendCapabilities capabilities() {
        return LocatorBackendCapabilities.standardStrategies();
    }

    /** Executes one focused query within the supplied hierarchical scope. */
    LocatorBackendSearchResult find(
            LocatorBackendQuery query,
            LocatorScope scope,
            LocatorConfig config,
            Duration timeout,
            int candidateLimit);
}
