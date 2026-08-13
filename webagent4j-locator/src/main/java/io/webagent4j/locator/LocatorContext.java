package io.webagent4j.locator;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable resolution context carrying the backend, hierarchical scope and configuration.
 *
 * @param backend concrete browser discovery port
 * @param scope current hierarchical scope
 * @param config engine configuration
 */
public record LocatorContext(ILocatorBackend backend, LocatorScope scope, LocatorConfig config) {

    /** Validates context data. */
    public LocatorContext {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(config, "config");
    }

    /** Creates an unscoped page context. */
    public static LocatorContext page(ILocatorBackend backend, LocatorConfig config) {
        return new LocatorContext(backend, LocatorScope.page(), config);
    }

    /** Returns a copy scoped to the supplied element descendants. */
    public LocatorContext within(IElement element) {
        Objects.requireNonNull(element, "element");
        String name = element.accessibleName();
        String description = element.role() + (name.isBlank() ? "" : " \"" + safe(name) + "\"");
        return new LocatorContext(backend, scope.within(element, description), config);
    }

    /** Returns the definition override or configured budget timeout. */
    public Duration timeoutFor(LocatorDefinition definition) {
        return definition.timeout().orElse(config.resolutionBudget().timeout());
    }

    private static String safe(String value) {
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 77) + "...";
    }
}
