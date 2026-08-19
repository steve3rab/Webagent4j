package io.webagent4j.browser.playwright;

import io.webagent4j.browser.FrameDefinition;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ILocatorScope;
import java.util.Objects;

/**
 * One entry in the single ordered chain of scopes accumulated by {@code within(...)} calls on
 * {@link PlaywrightFind}/{@link PlaywrightLocator}.
 *
 * <p>An explicit element scope and a structured scope are resolved differently - see {@link
 * PlaywrightScopeResolver} - but both kinds are appended to the <em>same</em> ordered list instead
 * of being tracked in two separate structures. That is deliberate: keeping only structured scopes
 * pending while applying an element scope immediately to the base context would silently reorder a
 * mixed chain relative to how the caller declared it (an element scope declared after a structured
 * scope would end up resolved before it). Declaration order is the sole source of truth for
 * resolution order; this type exists only to let one list hold both kinds without losing which is
 * which.
 */
sealed interface IPendingScope {

    /** An explicit element scope, applied as-is with no further resolution needed. */
    record Element(IElement element) implements IPendingScope {
        public Element {
            Objects.requireNonNull(element, "element");
        }
    }

    /** A structured scope, re-resolved against live state at every terminal operation. */
    record Structured(ILocatorScope<IElement> scope) implements IPendingScope {
        public Structured {
            Objects.requireNonNull(scope, "scope");
        }
    }

    /**
     * A frame boundary, re-resolved against live state at every terminal operation: unlike {@link
     * Element} and {@link Structured}, this entry does not narrow the current document - it starts
     * a fresh one, since a frame is a separate document/browsing context, never a descendant DOM
     * element of the page that contains its {@code <iframe>}. See {@link
     * PlaywrightScopeResolver#resolveFrameScope}.
     */
    record Frame(FrameDefinition definition) implements IPendingScope {
        public Frame {
            Objects.requireNonNull(definition, "definition");
        }
    }
}
