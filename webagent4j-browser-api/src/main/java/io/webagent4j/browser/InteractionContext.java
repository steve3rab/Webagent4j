package io.webagent4j.browser;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ILocatorScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable semantic scope describing the element or text region that should constrain a locator.
 *
 * <p>Interaction contexts are a thin, backend-neutral wrapper around the existing scoped locator
 * model. They are intentionally small: a context is either an explicit element scope or a textual
 * descriptor that resolves to a semantic region before the main target query is executed.
 */
public record InteractionContext(Optional<IElement> scope, List<String> containingText)
        implements ILocatorScope<IElement> {

    /** Creates an empty context that leaves the current page or ancestor scope unchanged. */
    public static InteractionContext context() {
        return new InteractionContext(Optional.empty(), List.of());
    }

    /** Validates the context and defensively copies its text constraints. */
    public InteractionContext {
        scope = Objects.requireNonNull(scope, "scope");
        containingText = List.copyOf(Objects.requireNonNull(containingText, "containingText"));
    }

    /** Returns a new context scoped to the supplied element. */
    public InteractionContext within(IElement element) {
        return new InteractionContext(
                Optional.of(Objects.requireNonNull(element, "element")), containingText);
    }

    /**
     * Adds a semantic text requirement that should resolve to the relevant container before the
     * target lookup.
     */
    public InteractionContext containingText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text cannot be blank");
        }
        List<String> next = new ArrayList<>(containingText);
        next.add(text.trim());
        return new InteractionContext(scope, List.copyOf(next));
    }

    /** Alias for a human-readable context label. */
    public InteractionContext named(String text) {
        return containingText(text);
    }

    @Override
    public Optional<IElement> scopeElement() {
        return scope;
    }
}
