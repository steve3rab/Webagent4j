package io.webagent4j.locator;

import io.webagent4j.dom.IElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable hierarchical resolution scope retained in diagnostics.
 *
 * @param type scope kind
 * @param root optional element root for element scopes
 * @param path safe human-readable scope path
 */
public record LocatorScope(LocatorScopeType type, Optional<IElement> root, List<String> path) {

    /** Validates the scope shape and defensively copies the path. */
    public LocatorScope {
        Objects.requireNonNull(type, "type");
        root = Objects.requireNonNull(root, "root");
        path = List.copyOf(Objects.requireNonNull(path, "path"));
        if (path.isEmpty()) {
            throw new IllegalArgumentException("scope path cannot be empty");
        }
        if (type == LocatorScopeType.ELEMENT && root.isEmpty()) {
            throw new IllegalArgumentException("element scope requires a root");
        }
        if (type == LocatorScopeType.PAGE && root.isPresent()) {
            throw new IllegalArgumentException("page scope cannot have an element root");
        }
    }

    /** Creates a page scope. */
    public static LocatorScope page() {
        return new LocatorScope(LocatorScopeType.PAGE, Optional.empty(), List.of("Page"));
    }

    /** Creates a child element scope and appends its safe description to the path. */
    public LocatorScope within(IElement element, String description) {
        List<String> nextPath = new ArrayList<>(path);
        nextPath.add(Objects.requireNonNull(description, "description"));
        return new LocatorScope(
                LocatorScopeType.ELEMENT,
                Optional.of(Objects.requireNonNull(element, "element")),
                nextPath);
    }
}
